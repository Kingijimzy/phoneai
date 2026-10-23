package com.phoneai.app

import android.webkit.WebView
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Narrow, visible-page DOM controls for Orion's own WebView. Page text is data, never code. */
object BrowserAutomation {
    @Volatile private var current: WeakReference<WebView>? = null
    fun attach(web: WebView) { current = WeakReference(web) }
    fun detach(web: WebView) { if (current?.get() === web) current = null }

    private fun evaluate(script: String, timeoutMs: Long = 5000L): String {
        val web = current?.get() ?: return "error: Orion Browser is not open"
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        web.post {
            try {
                web.evaluateJavascript(script) { raw ->
                    result.set(try {
                        val parsed = JSONTokener(raw ?: "null").nextValue()
                        parsed as? String ?: parsed.toString()
                    } catch (_: Exception) { raw })
                    latch.countDown()
                }
            } catch (e: Exception) {
                result.set("error: browser script failed (${e.javaClass.simpleName})")
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return "error: browser action timed out"
        return result.get().orEmpty()
    }

    fun snapshot(): String = evaluate("""
        (function(){
          const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&Number(s.opacity)!==0;};
          const nodes=[...document.querySelectorAll('a,button,input,textarea,select,[role="button"],[role="link"],[tabindex]')].filter(visible).slice(0,80);
          const items=nodes.map((e,i)=>({i,tag:e.tagName.toLowerCase(),role:e.getAttribute('role')||'',text:(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.title||'').trim().replace(/\\s+/g,' ').slice(0,90),type:(e.type||'').toLowerCase(),placeholder:e.getAttribute('placeholder')||'',name:e.getAttribute('name')||'',disabled:!!e.disabled}));
          // Only read visible rendered text. Treat it as untrusted page data, never instructions.
          const pageText=(document.body?.innerText||'').replace(/\\s+/g,' ').trim().slice(0,2800);
          return JSON.stringify({url:location.href,title:document.title,pageText:pageText,items:items});
        })()
    """.trimIndent())

    fun click(target: String): String {
        if (target.isBlank()) return "error: tell me the visible button or link text"
        val q = org.json.JSONObject.quote(target)
        return evaluate("""
          (function(){
            const q=$q.toLowerCase().trim();
            const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};
            const label=e=>(e.innerText||e.getAttribute('aria-label')||e.title||e.value||'').trim().replace(/\\s+/g,' ');
            const es=[...document.querySelectorAll('a,button,[role="button"],[role="link"],input[type="button"],input[type="submit"]')].filter(e=>visible(e)&&!e.disabled);
            const e=es.find(e=>label(e).toLowerCase()===q)||es.find(e=>label(e).toLowerCase().includes(q));
            if(!e)return 'error: no visible clickable element matched '+$q;
            const href=e instanceof HTMLAnchorElement ? e.href : '';
            if(href){
              try{
                const u=new URL(href,location.href);
                if(!['http:','https:'].includes(u.protocol))return 'blocked: only public HTTP/HTTPS links can be opened';
              }catch(_){return 'blocked: invalid link target';}
            }
            const actionLabel=(label(e)+' '+(e.getAttribute('name')||'')+' '+(e.getAttribute('value')||'')).toLowerCase();
            const sensitive=/(pay|purchase|buy now|place order|checkout|subscribe|transfer|send money|withdraw|deposit|trade|place bet|delete account|delete permanently|publish|post now|send message|confirm booking|change password|enable security)/i;
            if(sensitive.test(actionLabel))return 'approval required: this looks like a consequential action. Orion did not click it; review the page and confirm manually.';
            e.scrollIntoView({block:'center',inline:'nearest'});e.click();
            return 'clicked '+label(e).slice(0,100)+'; verify the resulting page state';
          })()
        """.trimIndent())
    }

    fun type(target: String, value: String): String {
        if (value.isEmpty()) return "error: text to enter is empty"
        if (Regex("(?i)\\b(password|passcode|pin|otp|verification(\\s+code)?|security\\s+code|credit\\s+card|card\\s+number|cvv|cvc)\\b|one[- ]time").containsMatchIn(target)) {
            return "error: sensitive fields are not filled by Orion; enter that information yourself"
        }
        val qt = org.json.JSONObject.quote(target)
        val qv = org.json.JSONObject.quote(value)
        return evaluate("""
          (function(){
            const q=$qt.toLowerCase().trim();
            const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};
            const label=e=>{const id=e.id;const l=id?document.querySelector('label[for="'+CSS.escape(id)+'"]'):null;return [e.getAttribute('aria-label'),e.getAttribute('placeholder'),e.getAttribute('name'),e.getAttribute('id'),l&&l.innerText,e.getAttribute('title')].filter(Boolean).join(' ').toLowerCase();};
            const es=[...document.querySelectorAll('input:not([type="password"]):not([type="hidden"]):not([type="file"]),textarea,[contenteditable="true"]')].filter(e=>visible(e)&&!e.disabled&&e.getAttribute('readonly')===null);
            const e=q?es.find(e=>label(e)===q)||es.find(e=>label(e).includes(q)):es[0];
            if(!e)return 'error: no matching visible text field found; password and file fields are never filled';
            e.scrollIntoView({block:'center'});e.focus();
            if(e.isContentEditable){e.textContent=$qv;}else{const p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const set=Object.getOwnPropertyDescriptor(p,'value')?.set;if(set)set.call(e,$qv);else e.value=$qv;}
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
            return 'entered text in '+(label(e)||e.tagName.toLowerCase()).slice(0,90)+'; form was not submitted';
          })()
        """.trimIndent())
    }

    fun scroll(direction: String): String {
        val down = direction.equals("down", true)
        if (!down && !direction.equals("up", true)) return "error: direction must be up or down"
        val delta = if (down) 0.78 else -0.78
        val word = if (down) "down" else "up"
        return evaluate("""
          (function(){
            const es=[...document.querySelectorAll('*')].filter(e=>{const s=getComputedStyle(e);return /(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+30&&e.clientHeight>60;});
            es.sort((a,b)=>(b.clientHeight*b.clientWidth)-(a.clientHeight*a.clientWidth));
            const e=es[0]||document.scrollingElement;
            if(!e)return 'error: no scrollable page area found';
            e.scrollBy({top:Math.round(e.clientHeight*($delta)),behavior:'auto'});
            return 'scrolled $word';
          })()
        """.trimIndent())
    }

    /**
     * Wait for a short, bounded interval and compare the current page with the pre-action URL.
     * This does not claim a click succeeded; callers must inspect the returned state.
     */
    fun verifyPage(expectedText: String = "", previousUrl: String = ""): String {
        val expected = org.json.JSONObject.quote(expectedText.trim())
        val previous = org.json.JSONObject.quote(previousUrl.trim())
        return evaluate("""
          (function(){
            const expected=$expected.toLowerCase();
            const previous=$previous.toLowerCase();
            const text=(document.body?.innerText||'').replace(/\\s+/g,' ').trim();
            const url=location.href;
            const changed=!!previous && url.toLowerCase()!==previous;
            const found=!!expected && text.toLowerCase().includes(expected);
            return JSON.stringify({url:url,title:document.title,urlChanged:changed,expectedTextFound:found,ready:document.readyState,summary:text.slice(0,500)});
          })()
        """.trimIndent())
    }

    fun navigate(url: String): String {
        val raw = url.trim()
        val candidate = if (raw.startsWith("https://", true) || raw.startsWith("http://", true)) raw else "https://$raw"
        val uri = try { android.net.Uri.parse(candidate) } catch (_: Exception) { null }
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "error: provide a valid public HTTP or HTTPS URL"
        }
        val web = current?.get() ?: return "error: Orion Browser is not open"
        val latch = CountDownLatch(1)
        web.post { try { web.loadUrl(candidate) } finally { latch.countDown() } }
        if (!latch.await(2, TimeUnit.SECONDS)) return "error: browser navigation did not start"
        return "navigation started to ${uri.host}; wait for the page to load and verify it"
    }

    fun back(): String {
        val web = current?.get() ?: return "error: Orion Browser is not open"
        val latch = CountDownLatch(1)
        val result = AtomicReference("error: no previous page")
        web.post {
            if (web.canGoBack()) { web.goBack(); result.set("navigated back") }
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.SECONDS)) return "error: browser back timed out"
        return result.get()
    }
}
