package com.htb.carplan.phone.login

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.htb.carplan.phone.R
import com.htb.carplan.phone.ui.CenterSheet
import com.htb.carplan.phone.ui.Corner
import com.htb.carplan.phone.ui.Palette
import com.htb.carplan.shared.data.SessionRepository
import com.htb.carplan.shared.data.placeholderName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 설정 탭 "로그인 연결" → the gate opens again even though the user skipped it before. */
object LoginGateBus { val reopen = MutableStateFlow(false) }

/**
 * 1장: 구글로 시작하기 · 나중에 (카카오는 뺌, 2026-10-30 정원: 시연만 하면 되니 구글만). The app is usable anonymously from the first second (익명 로그인);
 * logging in links that account so every group, event and record stays under the same uid.
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {
    private val sessions = SessionRepository()
    private val prefs = app.getSharedPreferences("login", Context.MODE_PRIVATE)
    private val _show = MutableStateFlow(needsGate())
    val show: StateFlow<Boolean> = _show.asStateFlow()
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    /** Set after a successful login: (suggested name, the account changed to a different uid). */
    val confirmName = MutableStateFlow<Pair<String, Boolean>?>(null)

    init { viewModelScope.launch { LoginGateBus.reopen.collect { if (it) { _show.value = true; LoginGateBus.reopen.value = false } } } }

    private fun needsGate() = sessions.isAnonymous() && !prefs.getBoolean("skipped", false)

    fun skip() { prefs.edit().putBoolean("skipped", true).apply(); _show.value = false }

    fun google(activity: Context) = run("구글") {
        val cm = CredentialManager.create(activity)
        val option = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false)
            .setServerClientId(activity.getString(R.string.default_web_client_id)).build()
        val res = cm.getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val c = res.credential
        if (c !is CustomCredential || c.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) error("구글 계정 정보를 받지 못했어요")
        val g = GoogleIdTokenCredential.createFrom(c.data)
        val r = sessions.linkGoogle(g.idToken, g.displayName, g.profilePictureUri?.toString())
        confirmName.value = (g.displayName ?: "") to r.switchedAccount
    }

    fun saveName(name: String) = viewModelScope.launch {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
        if (name.isNotBlank()) runCatching { sessions.updateName(uid, name) }
        confirmName.value = null
        _show.value = false
    }

    private fun run(what: String, block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true; message.value = null
        runCatching { block() }.onFailure { e ->
            message.value = when {
                e is GetCredentialCancellationException -> null
                e is NoCredentialException -> "이 기기에 구글 계정이 없어요. 기기 설정에서 계정을 추가하거나 실제 폰에서 해 주세요."
                e is androidx.credentials.exceptions.GetCredentialProviderConfigurationException || e.message?.contains("no provider dependencies") == true ->
                    "이 기기엔 구글 계정 서비스가 없어요(에뮬레이터). 실제 폰에서 로그인해 주세요. 지금은 [나중에 할게요]로 쓰면 돼요."
                e.message?.contains("취소") == true || e.message?.contains("cancel", true) == true -> null
                else -> "$what 로그인 실패: ${e.message}"
            }
        }
        busy.value = false
    }

}

@Composable
fun LoginGate(vm: LoginViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val confirm by vm.confirmName.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text("carplan", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Palette.TextStrong)
        Text("캘린더에 쓴 일정이 차를 움직여요", fontSize = 17.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { vm.google(context) }, enabled = !busy, shape = Corner,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (busy) "로그인 중…" else "구글로 시작하기", fontSize = 17.sp) }
        message?.let { Text(it, fontSize = 15.sp, color = Palette.Sunday, modifier = Modifier.padding(top = 10.dp)) }
        Text("로그인하면 그룹·일정·기록이 계정에 묶여 폰을 바꿔도 이어져요. 지금 둘러보다가 설정 탭에서 연결해도 돼요.", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(top = 14.dp))
        TextButton(onClick = vm::skip, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp)) { Text("나중에 할게요", color = Palette.TextSoft, fontSize = 16.sp) }
    }

    confirm?.let { (suggested, switched) ->
        // 1장 2: first time → confirm the name (Google name as the default, editable).
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        var name by remember { mutableStateOf(suggested.ifBlank { placeholderName(uid) }) }
        CenterSheet(onDismiss = {}) {
            Column {
                Text("이름 확인", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.TextStrong)
                Text(if (switched) "이미 쓰던 계정으로 들어왔어요. 이름을 확인해 주세요" else "그룹 멤버와 차 화면에 이렇게 보여요", fontSize = 15.sp, color = Palette.TextSoft, modifier = Modifier.padding(bottom = 10.dp))
                OutlinedTextField(name, { name = it.take(20) }, singleLine = true, shape = Corner, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            vm.saveName(name)
                            // A different uid means every screen's data changed: start the activity over.
                            if (switched) (context as? Activity)?.recreate()
                        },
                        enabled = name.isNotBlank(), shape = Corner, colors = ButtonDefaults.buttonColors(containerColor = Palette.Navy, contentColor = Color.White),
                    ) { Text("시작하기") }
                }
            }
        }
    }
}
