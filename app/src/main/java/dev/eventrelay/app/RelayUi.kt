package dev.eventrelay.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class SelectableApp(val packageName:String,val label:String)
internal fun filterSelectableApps(apps:List<SelectableApp>,ownPackage:String)=apps
    .filter{it.packageName!=ownPackage&&it.packageName!="com.google.android.gm"}
    .distinctBy{it.packageName}.sortedWith(compareBy<SelectableApp>{it.label.lowercase()}.thenBy{it.packageName})
@Suppress("DEPRECATION")
internal fun selectableApps(context:Context):List<SelectableApp> = try {
    filterSelectableApps(context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        .map{SelectableApp(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString())},context.packageName)
}catch(_:SecurityException){emptyList()}

internal fun queueStatus(status:String)=when(status){
    "PENDING"->"전송 대기";"SENDING"->"Gmail에 제출 중";"ACCEPTED"->"Gmail 접수 완료"
    "RETRYABLE_FAILURE"->"잠시 후 다시 시도";"PERMANENT_FAILURE"->"전송 실패"
    "OUTCOME_UNKNOWN"->"전송 결과 확인 필요";"BLOCKED_AUTH"->"계정 연결 필요 · 보류"
    "BLOCKED_DELIVERY"->"전송 꺼짐 · 보류";"BLOCKED_ACCOUNT_BINDING"->"계정 변경 · 보류"
    "BLOCKED_RULE"->"규칙 꺼짐 · 보류";"BLOCKED_POLICY"->"이전 항목 · 안전 보류"
    else->"상태 확인 필요"
}
internal fun authorizationStatus(code:String)=when(code){
    "AUTHORIZED_OWN_ACCOUNT"->"계정이 연결됐습니다. 전송은 별도로 켜야 합니다."
    "AUTHORIZATION_IN_PROGRESS"->"Google 계정 연결을 기다리고 있습니다."
    "AUTHORIZATION_CANCELLED","AUTHORIZATION_INTERRUPTED"->"연결을 취소했습니다. 전송은 꺼져 있습니다."
    "AUTHORIZATION_STALE_RESULT"->"이전 연결 결과는 반영하지 않았습니다."
    "AUTH_SCOPE_MISSING"->"Google 동의 화면에서 Gmail 보내기 권한을 허용해 주세요."
    "AUTH_SCOPE_UNEXPECTED"->"요청한 Gmail 보내기 권한과 달라 연결을 중단했습니다."
    "AUTH_ACCOUNT_MISMATCH","ACCOUNT_CHANGE_REQUIRED"->"계정 변경 버튼으로 기존 연결을 해제한 뒤 다시 연결해 주세요."
    "AUTH_TOKEN_MISSING"->"Google이 전송 토큰을 반환하지 않았습니다. 다시 연결해 주세요."
    "INVALID_EMAIL"->"Google 계정의 이메일 주소를 확인해 주세요."
    "CONFIGURATION_REQUIRED"->"이 빌드에서는 Google 계정 연결을 사용할 수 없습니다."
    else->"연결을 완료하지 못했습니다. Google 설정과 네트워크를 확인한 뒤 다시 시도해 주세요."
}
internal fun readableTime(millis:Long):String=DateTimeFormatter.ofPattern("MM.dd HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

internal data class DiagnosticSnapshot(val accountConnected:Boolean,val delivery:Boolean,val collectionPaused:Boolean,
    val smsPermission:Boolean,val notificationAccess:Boolean,val unfinished:Int,val unknown:Int,val collectorIssue:Boolean)
/** Whitelisted booleans/counts only. This API cannot accept event text, identities, or exceptions. */
internal fun safeDiagnostics(snapshot:DiagnosticSnapshot):String=buildString {
    appendLine("EventRelay 진단")
    appendLine("버전: "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")")
    appendLine("Android API: "+android.os.Build.VERSION.SDK_INT)
    appendLine("계정 연결: "+snapshot.accountConnected)
    appendLine("Gmail 전송: "+snapshot.delivery)
    appendLine("수집 일시정지: "+snapshot.collectionPaused)
    appendLine("SMS 권한: "+snapshot.smsPermission)
    appendLine("알림 접근: "+snapshot.notificationAccess)
    appendLine("미전송 항목 수: "+snapshot.unfinished.coerceAtLeast(0))
    appendLine("결과 미확인 수: "+snapshot.unknown.coerceAtLeast(0))
    appendLine("수집 오류 발생: "+snapshot.collectorIssue)
    append("본문·발신자·앱 목록·이메일·토큰은 포함하지 않습니다.")
}

@Composable internal fun RelayTheme(content:@Composable ()->Unit){
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF3446A1),onPrimary=Color.White,
        secondary=Color(0xFF147A72),background=Color(0xFFF4F6FB),surface=Color.White,
        surfaceVariant=Color(0xFFE9EDF6),onSurface=Color(0xFF172039),onSurfaceVariant=Color(0xFF56627B)),
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(18.dp),large=RoundedCornerShape(24.dp)),content=content)
}
@Composable internal fun SectionCard(title:String,content:@Composable ColumnScope.()->Unit){
    Card(modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(title,style=MaterialTheme.typography.titleLarge);content()
        }
    }
}
@Composable internal fun AppPickerDialog(apps:List<SelectableApp>,onSelect:(SelectableApp)->Unit,onDismiss:()->Unit){
    var search by remember{mutableStateOf("")}
    val filtered=apps.filter{it.label.contains(search,true)||it.packageName.contains(search,true)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("알림을 받을 앱 선택")},confirmButton={},
        dismissButton={TextButton(onClick=onDismiss){Text("취소")}},text={
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedTextField(search,{search=it},label={Text("앱 이름 검색")},singleLine=true,modifier=Modifier.testTag("app-search"))
                Text("홈 화면에서 실행할 수 있는 앱만 표시합니다.",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=340.dp)){
                    if(filtered.isEmpty())item{Text("선택할 앱이 없습니다.")}
                    items(filtered,key={it.packageName}){app->
                        Row(Modifier.fillMaxWidth().clickable{onSelect(app)}.testTag("app-"+app.packageName).padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceVariant){Text(app.label.take(1),Modifier.padding(10.dp))}
                            Text(app.label,Modifier.padding(top=9.dp),style=MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        })
}
