package com.phoneai.app

import org.json.JSONObject

internal object AgentHooks {
 data class Proof(val tool:String,val success:Boolean,val verified:Boolean,val summary:String)
 class ProofRecorder {
  private val proofs=ArrayList<Proof>()
  @Synchronized fun clear(){proofs.clear()}
  @Synchronized fun record(tool:String,result:String){
   val ok=!result.trimStart().startsWith("error",true)
   // Screen-change markers and OCR snippets are not proof that a consequential action completed.
   val summary=result.substringBefore("Current screen evidence").substringBefore("SCREEN EVIDENCE")
   val verified=Regex("\\b(message sent|email sent|sent successfully|delivered|read by recipient|deleted successfully|completed successfully)\\b",RegexOption.IGNORE_CASE).containsMatchIn(summary)
   proofs.add(Proof(tool,ok,verified,result.take(500))); while(proofs.size>80) proofs.removeAt(0)
  }
  @Synchronized fun snapshot():List<Proof> = proofs.toList()
 }
 data class GateDecision(val allowed:Boolean,val reason:String)
 private val phoneActionWords=Regex(
  "\\b(browser|navigate|automation|open|launch|start|tap|click|press|scroll|swipe|type|play|go to|go back|search|send|call|text|message|reply|email|whatsapp|telegram|bot|integration|integrations|mcp|workflow|workflows|price|crypto|bybit|market|record|turn|switch|screenshot|install|download|close|look|screen|fill|login|log in|sign in|research|browse|website|webpage|github|repository|repositories|release|issues|post|share|buy|order|book|find me|show me|check my|delete|write|compose|task|tasks|remind|reminder|todo|to-do|mark as done|remember|recall|forget|memory|memories|update memory|save this|undo|reverse last|undo that)\\b",
  RegexOption.IGNORE_CASE
 )
 fun isPhoneActionRequest(request:String):Boolean=phoneActionWords.containsMatchIn(request)
 fun verifySendEvidence(contact:String,message:String,screen:String):String {
  val target=contact.trim(); val body=message.trim()
  if(target.isBlank()||body.isBlank()) return "error: contact and exact message text are required."
  if(screen.isBlank()) return "UNVERIFIED: the current screen map is empty."
  val lines=screen.lineSequence().map{it.trim()}.filter{it.isNotBlank()}.toList()
  if(lines.none{it.contains(target,ignoreCase=true)}) return "error: verification failed: contact '$target' is not visible."
  val index=lines.indexOfFirst{it.contains(body,ignoreCase=true)}
  if(index<0) return "error: verification failed: exact outgoing message is not visible."
  val nearby=lines.subList(maxOf(0,index-2),minOf(lines.size,index+4))
  val statusLine=nearby.firstOrNull{it.length<=40&&Regex("(?i)\\b(sent|delivered|read)\\b").containsMatchIn(it)}
   ?: return "UNVERIFIED: contact and message are visible, but no short sent/delivered/read status is exposed near the message."
  val status=Regex("(?i)\\b(sent|delivered|read)\\b").find(statusLine)?.value.orEmpty()
  return "PASS: message sent to $target; exact message and status '$status' are visible."
 }
 private val risky=Regex("\\b(send|submit|post|publish|delete|remove|pay|purchase|buy|transfer|withdraw|trade|place order|call)\\b",RegexOption.IGNORE_CASE)
 private val explicit=Regex("\\b(send|submit|post|publish|delete|remove|pay|purchase|buy|transfer|withdraw|trade|place order|call|forget|erase|update memory|edit saved memory)\\b",RegexOption.IGNORE_CASE)
 fun scopeBlockReason(request:String,tool:String,args:JSONObject,targetLabel:String=""):String? {
  val target=when(tool){"click_text"->args.optString("text");"browser_click","browser_click_verify"->args.optString("target");"notification_action"->args.optString("action");else->""}
  val label=listOf(target,targetLabel).filter{it.isNotBlank()}.joinToString(" ")
  val riskyTool=tool in setOf("task_delete","forget","update_memory","undo_last")||(tool=="notification_action"&&args.optString("action").equals("reply",true))
  return if((risky.containsMatchIn(label)||riskyTool)&&!explicit.containsMatchIn(request)) "Request does not explicitly authorize this consequential action${if(label.isBlank())"" else " ('$label')"}." else null
 }
 fun requiresConfirmation(tool:String,args:JSONObject,targetLabel:String=""):Boolean {
  if(tool in setOf("forget","update_memory","undo_last")) return true
  if(tool=="notification_action"&&args.optString("action").equals("reply",true)) return true
  val target=when(tool){"click_text"->args.optString("text");"browser_click","browser_click_verify"->args.optString("target");"click"->targetLabel;else->""}
  if(risky.containsMatchIn(target)) return true
  return tool=="type"&&Regex("\\b(whatsapp|email|gmail|message|send|reply)\\b",RegexOption.IGNORE_CASE).containsMatchIn(targetLabel)
 }
 fun completionDecision(request:String,proofs:List<Proof>):GateDecision {
  val success=proofs.filter{it.success}
  if(success.isEmpty()) return GateDecision(false,"No successful tool result was recorded.")
  if(risky.containsMatchIn(request)&&success.none{it.verified}) return GateDecision(false,"No post-action evidence confirms the consequential outcome.")
  return GateDecision(true,"Action evidence recorded.")
 }
 fun awaitStableSnapshot(read:()->String,maxSamples:Int=5,stableSamples:Int=2,intervalMs:Long=120L):String {
  require(maxSamples>=1&&stableSamples>=1&&intervalMs>=0L)
  var previous=""; var stable=0; var latest=""
  repeat(maxSamples){i->
   latest=try{read()}catch(_:Exception){latest}
   val fp=latest.lineSequence().map{it.trim().lowercase()}.filter{it.isNotBlank()}.joinToString("|")
   if(i>0&&fp==previous) stable++ else stable=1
   if(stable>=stableSamples)return latest
   previous=fp; if(i<maxSamples-1&&intervalMs>0L)Thread.sleep(intervalMs)
  }
  return latest
 }
}
