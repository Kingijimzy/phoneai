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

    /** The in-app browser opens asynchronously; wait briefly instead of reporting "not open" mid-launch. */
    fun awaitWebView(timeoutMs: Long = 4000L): WebView? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            current?.get()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            try { Thread.sleep(100L) } catch (_: InterruptedException) { return null }
        }
    }

    private fun evaluate(script: String, timeoutMs: Long = 5000L): String {
        val web = awaitWebView() ?: return "error: Orion Browser is not open"
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

    /**
     * Fail closed on recognized financial/account portals. Read-only snapshots remain available,
     * but browser automation must not interact with banking, payment, wallet, or trading pages.
     */
    fun sensitivePageReason(): String? {
        val page = snapshot()
        val host = try { android.net.Uri.parse(org.json.JSONObject(page).optString("url")).host.orEmpty() }
            catch (_: Exception) { "" }
        if (host.isBlank()) return null
        // Match recognizable financial providers and bank brands, not generic words such as
        // "wallet" or "trading" that may appear in ordinary games, shops, or documentation.
        val financialHostTokens = listOf(
            "paypal", "stripe", "venmo", "cashapp", "revolut", "monzo", "wise", "opay",
            "palmpay", "kuda", "moniepoint", "flutterwave", "paytm", "phonepe", "binance",
            "coinbase", "kraken", "bybit", "kucoin", "okx", "chase", "hsbc", "barclays",
            "santander", "wellsfargo", "bankofamerica", "capitalone", "fidelity", "navyfederal",
            "gtbank", "zenith", "ubagroup", "firstbank", "ecobank", "accessbank", "stanbic",
            "fcmb", "polarisbank", "sterlingbank", "unionbank", "wema", "providus", "keystone",
            "paga", "payoneer", "skrill", "neteller"
        )
        val labels = host.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        return if (financialHostTokens.any { token ->
                labels.any { it == token } || host.lowercase().contains(token)
            }) {
            "Orion's safety gate paused browser automation on a recognized financial, wallet, or trading domain. Review and operate this site manually; read-only page inspection remains available."
        } else null
    }

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

    /**
     * One bounded browser step: record the current URL, click a visible control, then inspect
     * the resulting page. The returned verification is evidence for the agent, not proof that
     * the user's larger goal is complete.
     */
    fun clickAndVerify(target: String, expectedText: String = ""): String {
        val before = snapshot()
        val previousUrl = try { org.json.JSONObject(before).optString("url") } catch (_: Exception) { "" }
        val clickResult = click(target)
        if (!clickResult.startsWith("clicked ")) return clickResult
        try { Thread.sleep(850L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return "clicked; verification interrupted, inspect the page before continuing"
        }
        val verified = verifyPage(expectedText, previousUrl)
        return "Action: $clickResult\nPage check (not proof of task completion): $verified"
    }

    fun type(target: String, value: String): String {
        if (value.isEmpty()) return "error: text to enter is empty"
        if (Regex("(?i)\\b(password|passcode|pin|otp|verification(\\s+code)?|security\\s+code|credit\\s+card|card\\s+number|cvv|cvc|bank\\s+account|routing\\s+number|social\\s+security|national\\s+id|passport|recovery\\s+phrase|private\\s+key|seed\\s+phrase)\\b|one[- ]time").containsMatchIn(target)) {
            return "error: sensitive fields are not filled by Orion; enter that information yourself"
        }
        if (value.length > 2000) {
            return "error: text entry is limited to 2000 characters per action"
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

    /** Find a phrase in rendered page text and report a short surrounding excerpt in one call. */
    fun find(query: String): String {
        if (query.isBlank()) return "error: search phrase is required"
        val q = org.json.JSONObject.quote(query.trim().take(180))
        return evaluate("""
          (function(){
            const q=$q.toLowerCase();
            const text=(document.body?.innerText||'').replace(/\\s+/g,' ').trim();
            const i=text.toLowerCase().indexOf(q);
            if(i<0)return JSON.stringify({found:false,url:location.href,title:document.title});
            return JSON.stringify({found:true,url:location.href,title:document.title,index:i,excerpt:text.slice(Math.max(0,i-220),Math.min(text.length,i+q.length+420))});
          })()
        """.trimIndent())
    }

    /** Extract complete visible HTML tables in one call, keeping the total payload bounded. */
    fun extract(): String = evaluate("""
        (function(){
          const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};
          const all=[...document.querySelectorAll('table')].filter(visible);
          const accepted=[];
          for(let ti=0;ti<all.length&&accepted.length<12;ti++){
            const t=all[ti];
            const table={index:ti,caption:(t.caption?.innerText||'').trim().slice(0,100),
              rows:[...t.querySelectorAll('tr')].map(tr=>[...tr.querySelectorAll('th,td')].map(cell=>(cell.innerText||'').replace(/\\s+/g,' ').trim().slice(0,120)))};
            if(table.rows.length>100||table.rows.some(row=>row.length>20)) continue;
            const probe={url:location.href,title:document.title,tables:accepted.concat([table]),tableCount:all.length,omittedTables:0};
            if(JSON.stringify(probe).length<=12000) accepted.push(table);
          }
          const result={url:location.href,title:document.title,tables:accepted,tableCount:all.length,omittedTables:Math.max(0,all.length-accepted.length)};
          if(accepted.length===0&&all.length>0)result.note='Tables were too large for a safe single response; no partial table was returned.';
          if(all.length===0)result.summary=(document.body?.innerText||'').replace(/\\s+/g,' ').trim().slice(0,1000);
          return JSON.stringify(result);
        })()
    """.trimIndent(), 7000L)

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
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            !uri.userInfo.isNullOrBlank() || uri.host.orEmpty().any { it.isWhitespace() }) {
            return "error: provide a valid HTTP or HTTPS URL without embedded credentials"
        }
        val web = awaitWebView() ?: return "error: Orion Browser is not open"
        val latch = CountDownLatch(1)
        web.post { try { web.loadUrl(candidate) } finally { latch.countDown() } }
        if (!latch.await(2, TimeUnit.SECONDS)) return "error: browser navigation did not start"
        return "navigation started to ${uri.host}; wait for the page to load and verify it"
    }

    /**
     * Explicitly hands control back to the person for login, CAPTCHA, MFA, or other
     * site-controlled steps. Orion never attempts to solve or bypass these challenges.
     */
    fun handoff(reason: String): String {
        val clean = reason.trim().replace(Regex("\\s+"), " ").take(180)
        val page = snapshot()
        val url = try { org.json.JSONObject(page).optString("url") } catch (_: Exception) { "" }
        val host = try { android.net.Uri.parse(url).host.orEmpty() } catch (_: Exception) { "" }
        return "BROWSER HANDOFF REQUIRED. Automation must stop here. Ask the user to complete this step manually in Orion Browser" +
            (if (clean.isNotBlank()) ": $clean" else "") +
            (if (host.isNotBlank()) " on $host" else "") +
            ". Do not read or request passwords, one-time codes, recovery phrases, or payment credentials. After the user says they are done, take a fresh browser_snapshot before continuing."
    }

    /** Bounded local polling; no model/API request is made while waiting for visible text. */
    fun waitFor(text: String, seconds: Double = 8.0): String {
        val expected = text.trim()
        if (expected.isBlank()) return "error: wait_for needs non-empty visible text"
        val timeoutMs = (seconds.coerceIn(0.5, 30.0) * 1000).toLong()
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = snapshot()
            if (last.contains(expected, ignoreCase = true)) {
                return "found requested text after local wait: ${expected.take(120)}\n" + last.take(2500)
            }
            try { Thread.sleep(350L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return "error: browser wait was interrupted"
            }
        }
        return "error: timed out waiting for visible browser text: ${expected.take(120)}. Last page state: " + last.take(1200)
    }

    /** Ask the current page to open its native file picker; the user must choose the file. */
    fun upload(target: String): String {
        val block = sensitivePageReason()
        if (block != null) return "error: safety gate blocked upload. $block"
        val label = org.json.JSONObject.quote(target.trim())
        val result = evaluate("""
            (function(){
              const q=$label.toLowerCase();
              const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};
              const files=[...document.querySelectorAll('input[type="file"]')].filter(visible);
              const labelOf=e=>(e.getAttribute('aria-label')||e.getAttribute('name')||e.id||e.title||'').toLowerCase();
              const e=files.find(e=>!q||labelOf(e)===q||labelOf(e).includes(q));
              if(!e)return 'error: no visible file upload control matched; open the upload form first';
              e.click();
              return 'native file picker requested. Choose the file yourself and review the page before submitting; Orion will not select or submit a file automatically.';
            })()
        """.trimIndent())
        return if (result.startsWith("native file picker requested")) {
            "BROWSER HANDOFF REQUIRED: $result Automation is paused. Choose the file yourself, review the page, and tell Orion when to continue."
        } else result
    }

    fun back(): String {
        val web = awaitWebView() ?: return "error: Orion Browser is not open"
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
