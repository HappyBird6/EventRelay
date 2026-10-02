package dev.eventrelay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private enum class ConfirmationKind { ENABLE_DELIVERY, DISABLE_RULE, CHANGE_ACCOUNT, DISCONNECT_ACCOUNT, DELETE_QUEUE }
private data class Confirmation(val kind:ConfirmationKind,val impact:QueueImpact,val rule:StoredRule?=null)

class MainActivity:ComponentActivity(){
    private var authorization:GoogleGmailAuthorization?=null
    private var authorizationNotice by mutableStateOf("")
    private var authorizationBusy by mutableStateOf(false)
    private var uiRevision by mutableIntStateOf(0)
    private var permissionRefresh by mutableIntStateOf(0)
    private val authorizationResolution=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        if(result.resultCode==RESULT_OK) authorization?.consumeResolution(result.data,::onAuthorizationResult)
            ?: onAuthorizationResult("AUTHORIZATION_INTERRUPTED")
        else {authorization?.cancel();onAuthorizationResult("AUTHORIZATION_CANCELLED")}
    }
    private val smsPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){permissionRefresh++}
    private fun onAuthorizationResult(code:String){
        authorizationNotice=code;authorizationBusy=code=="AUTHORIZATION_IN_PROGRESS";uiRevision++
    }
    override fun onResume(){super.onResume();permissionRefresh++}
    override fun onDestroy(){authorization?.cancel();super.onDestroy()}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        RelayRuntime.initialize(this)
        setContent{RelayTheme{RelayScreen()}}
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun RelayScreen(){
        val preferences=getSharedPreferences("settings",MODE_PRIVATE)
        val dao=remember{RelayRuntime.db.dao()}
        val ruleFlow=remember{dao.watchRules()};val queueFlow=remember{dao.watchQueue()};val countFlow=remember{dao.watchCounts()}
        val rules by ruleFlow.collectAsState(initial=emptyList())
        val queue by queueFlow.collectAsState(initial=emptyList())
        val counts by countFlow.collectAsState(initial=QueueCounts(0,0,0))
        val policy=remember{QueuePolicy(this)}
        val scope=rememberCoroutineScope()
        val apps=remember(permissionRefresh){selectableApps(this)}
        val savedEmail=remember(uiRevision,permissionRefresh){preferences.getString("gmailOwnEmail",null)}
        val delivery=remember(uiRevision,permissionRefresh){gmailDeliveryEnabled(this)}
        val paused=remember(uiRevision,permissionRefresh){preferences.getBoolean("collectionPaused",false)}
        val smsGranted=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
        val notificationGranted=Settings.Secure.getString(contentResolver,"enabled_notification_listeners")
            ?.split(':')?.any{it.substringBefore('/')==packageName}==true
        var tab by remember{mutableStateOf("홈")}
        val pageScroll=rememberScrollState()
        LaunchedEffect(tab){pageScroll.scrollTo(0)}
        var ownEmail by remember{mutableStateOf(savedEmail.orEmpty())}
        var name by remember{mutableStateOf("")}
        var channel by remember{mutableStateOf("SMS")}
        var source by remember{mutableStateOf("")}
        var selectedLabel by remember{mutableStateOf<String?>(null)}
        var keyword by remember{mutableStateOf("")}
        var field by remember{mutableStateOf("body")}
        var operator by remember{mutableStateOf("contains")}
        val conditions=remember{mutableStateListOf<String>()}
        var picker by remember{mutableStateOf(false)}
        var confirmation by remember{mutableStateOf<Confirmation?>(null)}
        var preview by remember{mutableStateOf<JSONObject?>(null)}
        var notice by remember{mutableStateOf("")}
        var busy by remember{mutableStateOf(false)}
        fun action(block:suspend()->Unit){
            if(busy)return
            busy=true
            scope.launch {
                try{block();uiRevision++}
                catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
                catch(_:Exception){notice="작업을 완료하지 못했습니다. 상태를 확인한 뒤 다시 시도해 주세요."}
                finally{busy=false}
            }
        }
        fun ask(kind:ConfirmationKind,rule:StoredRule?=null){
            action{confirmation=Confirmation(kind,if(rule==null)policy.unfinishedImpact()else policy.ruleImpact(rule),rule)}
        }
        fun changeChannel(value:String){
            if(channel!=value){
                channel=value;source="";selectedLabel=null;field="body";keyword="";conditions.clear()
            }
        }

        Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
            NavigationBar {
                listOf("홈" to "◉","규칙" to "≡","기록" to "↻","설정" to "⚙").forEach{(label,icon)->
                    NavigationBarItem(selected=tab==label,onClick={tab=label},
                        icon={Text(icon)},label={Text(label)},modifier=Modifier.testTag("nav-"+label))
                }
            }
        }){insets->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(pageScroll).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    Column {Text("EventRelay",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                        Text("휴대폰의 이벤트를 나에게",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.small){
                        Text(if(delivery)"전송 켜짐"else"전송 OFF",Modifier.padding(10.dp),style=MaterialTheme.typography.labelLarge)
                    }
                }
                if(notice.isNotEmpty())Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium){
                    Text(notice,Modifier.padding(14.dp).testTag("notice"),style=MaterialTheme.typography.bodyMedium)
                }
                when(tab){
                    "홈"->{
                        SectionCard("내 이벤트 현황"){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                Column{Text(counts.unfinished.toString(),style=MaterialTheme.typography.headlineSmall);Text("미전송")}
                                Column{Text(counts.accepted.toString(),style=MaterialTheme.typography.headlineSmall);Text("Gmail 접수")}
                                Column{Text(counts.unknown.toString(),style=MaterialTheme.typography.headlineSmall);Text("결과 확인 필요")}
                            }
                            Text("규칙에 맞는 원문만 보관합니다. Gmail 접수와 외부 서비스의 처리 완료는 서로 다릅니다.",style=MaterialTheme.typography.bodySmall)
                        }
                        SectionCard("계정 연결과 전송"){
                            Text("Gmail 전송: "+if(delivery)"켜짐"else"꺼짐",style=MaterialTheme.typography.titleMedium)
                            Text(savedEmail?.let{"연결된 계정: "+it}?:"아직 연결된 계정이 없습니다.",style=MaterialTheme.typography.bodyMedium)
                            Text("Google 계정 연결만으로는 전송하지 않습니다. 전송을 켜도 이전 항목은 자동으로 보내지 않습니다.",style=MaterialTheme.typography.bodySmall)
                            if(BuildConfig.GMAIL_ENABLED&&savedEmail==null)
                                OutlinedTextField(ownEmail,{ownEmail=it},label={Text("자신의 Google 계정 이메일")},singleLine=true,
                                    modifier=Modifier.fillMaxWidth().testTag("account-email"))
                            Button(enabled=BuildConfig.GMAIL_ENABLED&&!authorizationBusy&&!busy&&(savedEmail!=null||isSafeEmail(ownEmail)),
                                modifier=Modifier.fillMaxWidth().testTag("connect-account"),onClick={
                                    action{
                                        policy.pauseForAuthorization()
                                        authorizationBusy=true;authorizationNotice="AUTHORIZATION_IN_PROGRESS"
                                        val controller=authorization?:GoogleGmailAuthorization(this@MainActivity).also{authorization=it}
                                        controller.request(savedEmail?:ownEmail,{pending->
                                            authorizationResolution.launch(IntentSenderRequest.Builder(pending).build())
                                        },::onAuthorizationResult)
                                    }
                                }){Text(if(!BuildConfig.GMAIL_ENABLED)"Google 계정 연결 (사용 불가)"else if(savedEmail==null)"Google 계정 연결"else"Google 계정 다시 연결")}
                            if(authorizationBusy)TextButton(onClick={authorization?.cancel();onAuthorizationResult("AUTHORIZATION_CANCELLED")}){
                                Text("연결 취소")
                            }
                            if(authorizationNotice.isNotEmpty())Text(authorizationStatus(authorizationNotice),style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled=BuildConfig.GMAIL_ENABLED&&!authorizationBusy&&!busy,modifier=Modifier.fillMaxWidth(),
                                onClick={if(delivery)action{policy.stopDelivery();notice="전송을 껐습니다. 이미 시작한 요청은 취소되지 않을 수 있습니다."}
                                    else if(paused)notice="수집 일시정지를 먼저 해제해 주세요."
                                    else if(savedEmail==null)notice="Google 계정을 먼저 연결해 주세요."
                                    else ask(ConfirmationKind.ENABLE_DELIVERY)}){
                                Text(if(delivery)"Gmail 전송 끄기"else"Gmail 전송 켜기")
                            }
                        }
                        SectionCard("수집 권한"){
                            Text("SMS: "+if(smsGranted)"허용됨"else"허용 필요")
                            OutlinedButton(onClick={smsPermission.launch(Manifest.permission.RECEIVE_SMS)}){Text("SMS 권한 설정")}
                            Text("알림 접근: "+if(notificationGranted)"허용됨"else"허용 필요")
                            OutlinedButton(onClick={startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}){Text("알림 접근 설정")}
                            Text("선택한 앱과 규칙에 맞지 않는 본문은 보관하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                        }
                        SectionCard("전송 없는 연습"){
                            Text("발신자 DEMO, 본문 EventRelay 데모인 문자 규칙으로 화면과 기록을 연습할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled=!busy,onClick={action{
                                RelayRuntime.collect(this@MainActivity,"SMS","DEMO","synthetic-demo-record-v1",1735689600000L,null,
                                    "EventRelay 데모",JSONObject().put("sender","DEMO").put("body","EventRelay 데모").put("synthetic",true))
                                notice="연습 항목은 전송하지 않습니다. 규칙이 일치하면 기록에서 확인하세요."
                            }}){Text("연습 문자 확인")}
                        }
                    }
                    "규칙"->{
                        SectionCard("새 규칙"){
                            OutlinedTextField(name,{name=it},label={Text("규칙 이름")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("rule-name"))
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilterChip(selected=channel=="SMS",onClick={changeChannel("SMS")},label={Text("문자 (SMS)")},modifier=Modifier.testTag("channel-sms"))
                                FilterChip(selected=channel=="NOTIFICATION",onClick={changeChannel("NOTIFICATION")},label={Text("앱 알림")},modifier=Modifier.testTag("channel-notification"))
                            }
                            if(channel=="SMS")OutlinedTextField(source,{source=it},label={Text("정확한 SMS 발신자")},singleLine=true,
                                modifier=Modifier.fillMaxWidth().testTag("rule-source"))
                            else OutlinedButton(onClick={picker=true},modifier=Modifier.fillMaxWidth().testTag("choose-app")){
                                Text(selectedLabel?.let{"선택한 앱: "+it}?:"앱 선택")
                            }
                            Text("추가 조건은 모두 일치해야 합니다.",style=MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilterChip(selected=field=="body",onClick={field="body"},label={Text("본문")},modifier=Modifier.testTag("field-body"))
                                if(channel=="NOTIFICATION")FilterChip(selected=field=="title",onClick={field="title"},label={Text("제목")},modifier=Modifier.testTag("field-title"))
                            }
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                listOf("contains" to "포함","equals" to "정확히 일치","not_contains" to "포함하지 않음").forEach{(value,label)->
                                    FilterChip(selected=operator==value,onClick={operator=value},label={Text(label)})
                                }
                            }
                            OutlinedTextField(keyword,{keyword=it},label={Text("조건 문자열")},modifier=Modifier.fillMaxWidth().testTag("rule-keyword"))
                            OutlinedButton(enabled=keyword.isNotEmpty(),onClick={
                                conditions+=JSONObject().put("field",field).put("operator",operator).put("value",keyword).toString();keyword=""
                            }){Text("AND 조건 추가")}
                            conditions.forEachIndexed{index,raw->
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                    Text(conditionLabel(JSONObject(raw)),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                                    TextButton(onClick={conditions.removeAt(index)}){Text("제거")}
                                }
                            }
                            if(keyword.isNotEmpty())Text("입력한 조건을 AND 조건 추가로 반영해야 저장할 수 있습니다.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                            Button(enabled=keyword.isEmpty()&&name.isNotBlank()&&source.isNotBlank()&&source!=packageName&&source!="com.google.android.gm"&&!busy,
                                modifier=Modifier.fillMaxWidth().testTag("save-rule"),onClick={action{
                                    val json=JSONArray();conditions.forEach{json.put(JSONObject(it))}
                                    RelayRuntime.db.dao().saveRule(StoredRule(UUID.randomUUID().toString(),name,channel,source,json.toString(),
                                        enabledSince=System.currentTimeMillis(),sourceLabel=selectedLabel))
                                    name="";source="";selectedLabel=null;keyword="";conditions.clear()
                                    notice="규칙을 저장했습니다. Gmail 전송은 별도로 켜야 합니다."
                                }}){Text("규칙 저장")}
                        }
                        Text("내 규칙",style=MaterialTheme.typography.titleLarge)
                        if(rules.isEmpty())SectionCard("첫 규칙을 만들어 보세요"){Text("문자의 발신자 또는 알림 앱을 선택하면 시작할 수 있습니다.")}
                        rules.forEach{rule->
                            SectionCard(rule.name){
                                Text(if(rule.channel=="SMS")"문자 · "+rule.source else"알림 · "+(rule.sourceLabel?:apps.find{it.packageName==rule.source}?.label?:"알림 앱"),
                                    style=MaterialTheme.typography.bodyMedium)
                                val json=JSONArray(rule.conditions)
                                if(json.length()==0)Text("발신자 또는 앱이 일치하면 적용",style=MaterialTheme.typography.bodySmall)
                                for(index in 0 until json.length())Text(conditionLabel(json.getJSONObject(index)),style=MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                    Text(if(rule.enabled)"활성"else"일시중지",Modifier.padding(top=12.dp))
                                    Switch(checked=rule.enabled,enabled=!busy,modifier=Modifier.testTag("rule-toggle-"+rule.id),onCheckedChange={enabled->
                                        if(enabled)action{policy.enableRule(rule);notice="규칙을 켰습니다. 이전 항목은 보류하며 이후 이벤트만 적용합니다."}
                                        else ask(ConfirmationKind.DISABLE_RULE,rule)
                                    })
                                }
                            }
                        }
                    }
                    "기록"->{
                        Text("이벤트 기록",style=MaterialTheme.typography.titleLarge)
                        Text("이전 보류 항목은 자동으로 재개하지 않습니다. 최근 100개 기록을 표시합니다.",style=MaterialTheme.typography.bodySmall)
                        if(queue.isEmpty())SectionCard("아직 기록이 없습니다"){Text("규칙에 일치하는 이벤트가 생기면 여기에 표시됩니다.")}
                        queue.forEach{event->
                            SectionCard(if(event.channel=="SMS")"문자 · "+event.source else event.sourceLabel?:apps.find{it.packageName==event.source}?.label?:"앱 알림"){
                                Text(queueStatus(event.status),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                                Text("수집 "+readableTime(event.observedAt),style=MaterialTheme.typography.bodySmall)
                                if(event.status=="OUTCOME_UNKNOWN")Text("중복 전송을 피하기 위해 자동으로 다시 보내지 않습니다.",style=MaterialTheme.typography.bodySmall)
                                TextButton(enabled=!busy,onClick={action{
                                    RelayRuntime.db.dao().payload(event.id)?.let{preview=externalMailPayload(JSONObject(it))}
                                }}){Text("원문 보기")}
                            }
                        }
                    }
                    "설정"->{
                        SectionCard("계정 관리"){
                            Text(savedEmail?:"연결된 계정 없음")
                            Text("계정을 바꾸거나 해제하면 전송이 꺼지고 기존 항목은 보류됩니다. 새 계정으로 이전 항목을 보내지 않습니다.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled=savedEmail!=null&&!busy,onClick={ask(ConfirmationKind.CHANGE_ACCOUNT)},modifier=Modifier.testTag("change-account")){Text("계정 변경")}
                            TextButton(enabled=savedEmail!=null&&!busy,onClick={ask(ConfirmationKind.DISCONNECT_ACCOUNT)},modifier=Modifier.testTag("disconnect-account")){Text("계정 연결 해제")}
                        }
                        SectionCard("보관과 삭제"){
                            Text("미전송 항목 "+counts.unfinished+"개")
                            Text("보류 중인 원문은 직접 삭제할 때까지 기기에 남습니다. 이미 시작한 전송은 삭제해도 취소되지 않을 수 있습니다.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled=counts.unfinished>0&&!busy,onClick={ask(ConfirmationKind.DELETE_QUEUE)},modifier=Modifier.testTag("delete-queue")){Text("미전송 항목 삭제")}
                            var days by remember{mutableIntStateOf(preferences.getInt("retentionDays",7))}
                            Text("Gmail 접수 / 영구 실패 기록: "+days+"일 보관")
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                listOf(1,7,30).forEach{value->FilterChip(selected=days==value,onClick={
                                    days=value;preferences.edit().putInt("retentionDays",value).apply()
                                },label={Text(value.toString()+"일")})}
                            }
                        }
                        SectionCard("수집과 개인정보"){
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                                Text("수집 일시정지",Modifier.padding(top=12.dp))
                                Switch(checked=paused,enabled=!busy,onCheckedChange={value->action{
                                    if(value)policy.stopDelivery()
                                    require(preferences.edit().putBoolean("collectionPaused",value).commit())
                                    notice=if(value)"수집과 전송을 멈췄습니다."else"수집을 재개했습니다. Gmail 전송은 꺼져 있습니다."
                                }})
                            }
                            Text("수집을 다시 켜도 Gmail 전송은 자동으로 켜지지 않습니다.",style=MaterialTheme.typography.bodySmall)
                            Text("인증번호와 비밀정보는 제외를 시도하지만 완벽히 식별할 수 없습니다. 원문 화면 보호는 유지됩니다.",style=MaterialTheme.typography.bodySmall)
                        }
                        SectionCard("문제 확인"){
                            Text("최근 수집 오류: "+if(preferences.contains("lastCollectorError"))"있음"else"없음")
                            Text("진단에는 버전, 권한 상태와 항목 수만 포함합니다.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={
                                val diagnostic=safeDiagnostics(DiagnosticSnapshot(savedEmail!=null,delivery,paused,smsGranted,notificationGranted,
                                    counts.unfinished,counts.unknown,preferences.contains("lastCollectorError")))
                                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("EventRelay 진단",diagnostic))
                                notice="민감정보 없는 진단을 복사했습니다."
                            },modifier=Modifier.testTag("copy-diagnostics")){Text("진단 복사")}
                            Text("EventRelay "+BuildConfig.VERSION_NAME,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if(picker)AppPickerDialog(apps,onSelect={app->source=app.packageName;selectedLabel=app.label;picker=false},onDismiss={picker=false})
        confirmation?.let{current->
            val title=when(current.kind){
                ConfirmationKind.ENABLE_DELIVERY->"앞으로 수집한 이벤트 전송 확인"
                ConfirmationKind.DISABLE_RULE->"규칙을 일시중지할까요?"
                ConfirmationKind.CHANGE_ACCOUNT->"계정 변경 준비"
                ConfirmationKind.DISCONNECT_ACCOUNT->"계정 연결을 해제할까요?"
                ConfirmationKind.DELETE_QUEUE->"미전송 항목 삭제 확인"
            }
            val description=when(current.kind){
                ConfirmationKind.ENABLE_DELIVERY->"받는 사람: "+savedEmail+"\n규칙에 맞는 SMS 및 앱 알림의 원문, 필요한 발신자·제목·앱 정보를 자신의 Gmail에 보냅니다. 이전 "+current.impact.count+"개 항목은 자동으로 보내지 않습니다. 이미 시작한 요청은 취소되지 않을 수 있습니다."
                ConfirmationKind.DISABLE_RULE->current.impact.count.toString()+"개 미전송 항목을 보류합니다. 규칙을 다시 켜도 이전 항목은 자동으로 보내지 않습니다."
                ConfirmationKind.CHANGE_ACCOUNT,ConfirmationKind.DISCONNECT_ACCOUNT->"기기에 저장한 연결 정보와 메모리 토큰만 제거합니다. Google 계정과 기존 메일은 삭제하지 않습니다. "+current.impact.count+"개 미전송 항목은 보류하며 새 계정으로 보내지 않습니다. 항목 삭제는 별도 확인이 필요합니다."
                ConfirmationKind.DELETE_QUEUE->current.impact.count.toString()+"개 미전송 항목과 원문을 기기에서 삭제합니다. 되돌릴 수 없습니다. Gmail 접수 기록은 유지합니다. 이미 시작한 전송은 취소되지 않을 수 있습니다."
            }
            AlertDialog(onDismissRequest={if(!busy)confirmation=null},title={Text(title)},text={Text(description)},
                dismissButton={TextButton(enabled=!busy,onClick={confirmation=null}){Text("취소")}},
                confirmButton={TextButton(enabled=!busy,modifier=Modifier.testTag("confirm-action"),onClick={action{
                    val done=when(current.kind){
                        ConfirmationKind.ENABLE_DELIVERY->policy.enableFutureDelivery()
                        ConfirmationKind.DISABLE_RULE->policy.disableRule(requireNotNull(current.rule),current.impact)
                        ConfirmationKind.CHANGE_ACCOUNT,ConfirmationKind.DISCONNECT_ACCOUNT->{
                            authorization?.cancel();authorizationBusy=false
                            policy.disconnectAccount(current.impact)
                        }
                        ConfirmationKind.DELETE_QUEUE->policy.deleteUnfinished(current.impact)
                    }
                    if(done){
                        confirmation=null
                        notice=when(current.kind){
                            ConfirmationKind.ENABLE_DELIVERY->"앞으로 수집한 이벤트만 전송합니다. 이전 항목은 보류됩니다."
                            ConfirmationKind.DISABLE_RULE->"규칙을 일시중지했습니다. 이전 항목은 보류됩니다."
                            ConfirmationKind.CHANGE_ACCOUNT->"이전 항목을 보류했습니다. 새 Google 계정을 연결해 주세요."
                            ConfirmationKind.DISCONNECT_ACCOUNT->"계정 연결을 해제했습니다. 이전 항목은 보류됩니다."
                            ConfirmationKind.DELETE_QUEUE->"확인한 미전송 항목을 삭제했습니다."
                        }
                        if(current.kind==ConfirmationKind.CHANGE_ACCOUNT||current.kind==ConfirmationKind.DISCONNECT_ACCOUNT){
                            ownEmail="";authorizationNotice="";if(current.kind==ConfirmationKind.CHANGE_ACCOUNT)tab="홈"
                        }
                    }else{
                        confirmation=current.copy(impact=if(current.rule==null)policy.unfinishedImpact()else policy.ruleImpact(current.rule))
                        notice="대상 항목이 달라졌습니다. 최신 개수를 확인하고 다시 결정해 주세요."
                    }
                }}){
                    Text(when(current.kind){ConfirmationKind.DELETE_QUEUE->current.impact.count.toString()+"개 삭제"
                        ConfirmationKind.ENABLE_DELIVERY->"확인하고 전송 켜기";else->"확인"})
                }})
        }
        preview?.let{value->
            AlertDialog(onDismissRequest={preview=null},title={Text("원문 미리보기")},text={
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(value.has("title"))Text(value.getString("title"),style=MaterialTheme.typography.titleMedium)
                    if(value.has("body"))Text(value.getString("body"))
                    value.optJSONArray("messages")?.let{messages->for(index in 0 until messages.length()){
                        val message=messages.getJSONObject(index)
                        if(message.has("sender"))Text(message.getString("sender"),style=MaterialTheme.typography.labelLarge)
                        Text(message.getString("text"))
                    }}
                    value.optJSONArray("additionalText")?.let{extra->for(index in 0 until extra.length())Text(extra.getJSONObject(index).getString("text"))}
                }
            },confirmButton={TextButton(onClick={preview=null}){Text("닫기")}})
        }
    }
}

private fun conditionLabel(condition:JSONObject):String{
    val field=if(condition.getString("field")=="title")"제목"else"본문"
    val operator=when(condition.getString("operator")){"equals"->"정확히 일치";"not_contains"->"포함하지 않음";else->"포함"}
    return field+" · "+operator+" · "+condition.getString("value")
}
