package com.htb.carplan.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.htb.carplan.shared.model.CarState
import com.htb.carplan.shared.model.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Reads the car through the paths verified on the emulator (docs/기획정리본.html 09장):
 * gear and fuel via android.car properties (Pleos getFuelPercent fails), range via the
 * Pleos Odometer, driving restriction via CarUxRestrictionsManager.
 */
class VehicleMonitor(private val context: Context) {
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state.asStateFlow()

    /** Fires once each time a passenger seat (not the driver's) becomes occupied (7장 4: "같이 타셨나요?"). */
    private val _passengerSeated = MutableSharedFlow<SeatValue>(extraBufferCapacity = 4)
    val passengerSeated: SharedFlow<SeatValue> = _passengerSeated
    /** 13장 Q34: the driver's belt went from buckled to unbuckled while parked (real car; the Virtual Console has no belt). */
    private val _driverUnbuckled = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val driverUnbuckled: SharedFlow<Unit> = _driverUnbuckled
    private var driverBuckled: Boolean? = null
    /** 8장 status band: how many passenger seats (not the driver's) are occupied right now. */
    private val _passengerCount = MutableStateFlow(0)
    val passengerCount: StateFlow<Int> = _passengerCount.asStateFlow()
    private val occupied = HashMap<SeatValue, Boolean>()

    /** Fires when the driver's door goes from closed to open (13장: "사람이 내렸다"). */
    private val _driverDoorOpened = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val driverDoorOpened: SharedFlow<Unit> = _driverDoorOpened
    private var driverDoorOpen = false

    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var fuelCapacity: Float? = null
    private var evCapacity: Float? = null

    /** 20장 디버그: forced fuel % / car type for the demo; null = real value. */
    @Volatile var fuelOverride: Double? = null
        set(v) { field = v; _state.update { it.copy(fuelPercent = v ?: realFuel) } }
    @Volatile var evOverride: Boolean? = null
        set(v) { field = v; _state.update { it.copy(isEv = v ?: realEv) } }
    private var realFuel: Double? = null
    private var realEv: Boolean? = null

    fun start() {
        val c = runCatching { Car.createCar(context) }.onFailure { Log.e(TAG, "Car connect failed", it) }.getOrNull() ?: return
        car = c
        val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        fuelCapacity = pm.readFloat(VehiclePropertyIds.INFO_FUEL_CAPACITY)
        evCapacity = pm.readFloat(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY)
        pm.listen(VehiclePropertyIds.GEAR_SELECTION) { v -> _state.update { it.copy(gear = (v as? Int).toGear()) } }
        realEv = when { (evCapacity ?: 0f) > 0f -> true; (fuelCapacity ?: 0f) > 0f -> false; else -> null }
        _state.update { it.copy(isEv = evOverride ?: realEv) }
        pm.listen(VehiclePropertyIds.FUEL_LEVEL) { v -> percentOf(v, fuelCapacity)?.let { p -> realFuel = p; _state.update { it.copy(fuelPercent = fuelOverride ?: p) } } }
        pm.listen(VehiclePropertyIds.EV_BATTERY_LEVEL) { v -> percentOf(v, evCapacity)?.let { p -> realFuel = p; _state.update { it.copy(fuelPercent = fuelOverride ?: p) } } }

        runCatching {
            val ux = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            _state.update { it.copy(drivingRestricted = ux.currentCarUxRestrictions.isRequiresDistractionOptimization) }
            ux.registerListener { r -> _state.update { it.copy(drivingRestricted = r.isRequiresDistractionOptimization) } }
        }.onFailure { Log.e(TAG, "UX restrictions unavailable", it) }

        vehicle = Vehicle(context).also { v ->
            v.initialize()
            runCatching {
                v.getDoor().registerDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), object : DoorPositionListener {
                    override fun onPositionUpdated(position: Int?) {
                        val open = (position ?: 0) > 0
                        if (open && !driverDoorOpen) _driverDoorOpened.tryEmit(Unit)
                        driverDoorOpen = open
                    }
                    override fun onFailed(e: Exception) { Log.w(TAG, "driver door: ${e.message}") }
                })
            }.onFailure { Log.w(TAG, "door position unavailable", it) }
            // Passenger seats only; the driver's seat never triggers the QR.
            listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT).forEach { seat ->
                runCatching {
                    v.getSeat().registerSeatOccupancy(SeatArea(seat), object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(value: Boolean?) {
                            val now = value == true
                            val was = occupied[seat] ?: false
                            occupied[seat] = now
                            _passengerCount.value = occupied.values.count { it }
                            if (now && !was) _passengerSeated.tryEmit(seat)
                        }
                        override fun onFailed(e: Exception) { Log.w(TAG, "seat $seat occupancy failed: ${e.message}") }
                    })
                }.onFailure { Log.w(TAG, "seat occupancy unavailable", it) }
            }
            v.getOdometer().getRangeRemainingDistance(
                // Pleos reports metres (500000 while the cluster shows 500 km).
                { m -> m?.let { r -> _state.update { it.copy(rangeKm = r.toDouble() / 1000) } } },
                { Log.w(TAG, "range read failed", it) },
            )
            // 13장: no belt listener in the SDK → while in P, read the driver's buckle every 2 s.
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (_state.value.gear == Gear.P) runCatching {
                        v.getSeat().getCurrentSeatBeltBuckledState(SeatArea(SeatValue.ROW_1_LEFT), { buckled ->
                            val now = buckled == true
                            if (driverBuckled == true && !now) _driverUnbuckled.tryEmit(Unit)
                            driverBuckled = now
                        }, { })
                    } else driverBuckled = null
                    handler.postDelayed(this, 2_000)
                }
            }, 2_000)
            // 15장: odometer has no listener → poll once a minute (Virtual Console: /vehicle/odometer).
            pollOdometer(v)
            handler.postDelayed(object : Runnable { override fun run() { vehicle?.let(::pollOdometer); handler.postDelayed(this, 60_000) } }, 60_000)
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        vehicle?.release()
        car?.disconnect()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun pollOdometer(v: Vehicle) {
        runCatching {
            v.getOdometer().getCurrentDistanceDriven(
                { km -> km?.let { d -> _state.update { it.copy(odometerKm = d.toDouble()) } } },
                { Log.w(TAG, "odometer read failed: ${it.message}") },
            )
        }.onFailure { Log.w(TAG, "odometer unavailable", it) }
    }

    private fun CarPropertyManager.readFloat(id: Int): Float? =
        runCatching { getProperty<Float>(id, 0)?.value }.getOrNull()

    private fun CarPropertyManager.listen(id: Int, onValue: (Any?) -> Unit) {
        runCatching {
            onValue(getProperty<Any>(id, 0)?.value)
            registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) = onValue(value.value)
                override fun onErrorEvent(propId: Int, zone: Int) = Unit
            }, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.onFailure { Log.w(TAG, "property $id unavailable: ${it.message}") }
    }

    private fun percentOf(value: Any?, capacity: Float?): Double? {
        val v = (value as? Float) ?: return null
        val cap = capacity?.takeIf { it > 0f } ?: return null
        return (v / cap * 100).toDouble().coerceIn(0.0, 100.0)
    }

    private fun Int?.toGear() = when (this) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        null -> Gear.UNKNOWN
        else -> if (this >= VehicleGear.GEAR_DRIVE) Gear.D else Gear.UNKNOWN
    }

    companion object {
        private const val TAG = "VehicleMonitor"
    }
}
