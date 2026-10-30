package com.htb.carplan.shared.net

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 ⇄ KATEC (the TM128 grid Opinet uses: Bessel ellipsoid, lat0 38°, lon0 128°, k 0.9999,
 * false easting 400 000 m, false northing 600 000 m, with the Korean 7-parameter datum shift).
 * Good to a few metres, which is all a "nearest gas station" search needs.
 */
object Katec {
    private const val A_WGS = 6378137.0
    private const val F_WGS = 1 / 298.257223563
    private const val A_BES = 6377397.155
    private const val F_BES = 1 / 299.1528128
    // towgs84 = -115.80, 474.99, 674.11, 1.16, -2.31, -1.63, 6.43 (Bessel → WGS84)
    private const val DX = -115.80; private const val DY = 474.99; private const val DZ = 674.11
    private val RX = Math.toRadians(1.16 / 3600); private val RY = Math.toRadians(-2.31 / 3600); private val RZ = Math.toRadians(-1.63 / 3600)
    private const val S = 1 + 6.43e-6
    private val LAT0 = Math.toRadians(38.0); private val LON0 = Math.toRadians(128.0)
    private const val K0 = 0.9999; private const val FE = 400_000.0; private const val FN = 600_000.0

    data class XY(val x: Double, val y: Double)

    fun fromWgs(lat: Double, lng: Double): XY {
        val (bLat, bLng) = datumShift(Math.toRadians(lat), Math.toRadians(lng), toBessel = true)
        return tmForward(bLat, bLng)
    }

    fun toWgs(x: Double, y: Double): Pair<Double, Double> {
        val (bLat, bLng) = tmInverse(x, y)
        val (wLat, wLng) = datumShift(bLat, bLng, toBessel = false)
        return Math.toDegrees(wLat) to Math.toDegrees(wLng)
    }

    // ---- 7-parameter Helmert through geocentric XYZ
    private fun datumShift(lat: Double, lng: Double, toBessel: Boolean): Pair<Double, Double> {
        val (aFrom, fFrom, aTo, fTo) = if (toBessel) listOf(A_WGS, F_WGS, A_BES, F_BES) else listOf(A_BES, F_BES, A_WGS, F_WGS)
        val e2 = 2 * fFrom - fFrom * fFrom
        val n = aFrom / sqrt(1 - e2 * sin(lat) * sin(lat))
        val x = n * cos(lat) * cos(lng); val y = n * cos(lat) * sin(lng); val z = n * (1 - e2) * sin(lat)
        val (x2, y2, z2) = if (toBessel) {
            // inverse of the Bessel→WGS Helmert: subtract translation, inverse rotate, un-scale (small-angle form)
            val xs = (x - DX) / S; val ys = (y - DY) / S; val zs = (z - DZ) / S
            Triple(xs + RZ * ys - RY * zs, -RZ * xs + ys + RX * zs, RY * xs - RX * ys + zs)
        } else {
            Triple(S * (x - RZ * y + RY * z) + DX, S * (RZ * x + y - RX * z) + DY, S * (-RY * x + RX * y + z) + DZ)
        }
        val e2t = 2 * fTo - fTo * fTo
        val p = sqrt(x2 * x2 + y2 * y2)
        var latT = atan2(z2, p * (1 - e2t))
        repeat(6) {
            val nt = aTo / sqrt(1 - e2t * sin(latT) * sin(latT))
            latT = atan2(z2 + e2t * nt * sin(latT), p)
        }
        return latT to atan2(y2, x2)
    }

    // ---- Transverse Mercator on Bessel
    private fun tmForward(lat: Double, lng: Double): XY {
        val f = F_BES; val a = A_BES
        val e2 = 2 * f - f * f; val ep2 = e2 / (1 - e2)
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        val t = tan(lat) * tan(lat); val c = ep2 * cos(lat) * cos(lat)
        val aa = cos(lat) * (lng - LON0)
        val m = meridian(lat, a, e2); val m0 = meridian(LAT0, a, e2)
        val x = FE + K0 * n * (aa + (1 - t + c) * aa.pow(3) / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa.pow(5) / 120)
        val y = FN + K0 * (m - m0 + n * tan(lat) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa.pow(4) / 24 + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa.pow(6) / 720))
        return XY(x, y)
    }

    private fun tmInverse(x: Double, y: Double): Pair<Double, Double> {
        val f = F_BES; val a = A_BES
        val e2 = 2 * f - f * f; val ep2 = e2 / (1 - e2)
        val m = meridian(LAT0, a, e2) + (y - FN) / K0
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) + (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) + (151 * e1.pow(3) / 96) * sin(6 * mu)
        val n1 = a / sqrt(1 - e2 * sin(phi1) * sin(phi1))
        val t1 = tan(phi1) * tan(phi1); val c1 = ep2 * cos(phi1) * cos(phi1)
        val r1 = a * (1 - e2) / (1 - e2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = (x - FE) / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720)
        val lng = LON0 + (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(phi1)
        return lat to lng
    }

    private fun meridian(lat: Double, a: Double, e2: Double): Double =
        a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256) * lat
            - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2.pow(3) / 1024) * sin(2 * lat)
            + (15 * e2 * e2 / 256 + 45 * e2.pow(3) / 1024) * sin(4 * lat)
            - (35 * e2.pow(3) / 3072) * sin(6 * lat))
}
