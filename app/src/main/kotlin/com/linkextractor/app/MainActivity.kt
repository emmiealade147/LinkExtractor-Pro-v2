package com.linkextractor.app

import android.app.*
import android.os.*
import android.content.*
import android.net.Uri
import android.webkit.*
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.*
import java.net.URI

data class Link(val url:String,val source:String)

class MainActivity:ComponentActivity(){
    lateinit var web:WebView
    val links=mutableStateListOf<Link>()
    var address by mutableStateOf("")
    var search by mutableStateOf("")
    var status by mutableStateOf("Enter a webpage URL")
    var deep by mutableStateOf(true)
    var https by mutableStateOf(false)
    var external by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var tab by mutableStateOf(0)
    var exportCsv by mutableStateOf(false)

    val txt=registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){u->
        if(u!=null)save(u,false)
    }
    val csv=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){u->
        if(u!=null)save(u,true)
    }

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        web=WebView(this)
        web.settings.javaScriptEnabled=true
        web.settings.domStorageEnabled=true
        web.webViewClient=object:WebViewClient(){
            override fun onPageStarted(v:WebView?,u:String?,f:android.graphics.Bitmap?){
                address=u.orEmpty();links.clear();busy=true;status="Loading page..."
            }
            override fun onPageFinished(v:WebView?,u:String?){
                address=u.orEmpty();scan()
            }
        }
        handle(intent)
        setContent{UI()}
    }

    override fun onNewIntent(i:Intent){
        super.onNewIntent(i);setIntent(i);handle(i)
    }

    fun handle(i:Intent?){
        val s=when(i?.action){
            Intent.ACTION_VIEW->i.dataString
            Intent.ACTION_SEND->i.getStringExtra(Intent.EXTRA_TEXT)
            else->i?.dataString
        }?:return
        val u=Regex("""https?://[^\s<>"']+""").find(s)?.value?:return
        address=u
        web.loadUrl(u)
    }

    fun scan(){
        busy=true
        status="Scanning webpage..."
        if(deep)scroll(0) else extract()
    }

    fun scroll(n:Int){
        if(n>=6){extract();return}
        web.evaluateJavascript(
            """window.scrollTo(0,document.documentElement.scrollHeight*${(n+1)/6.0});"""
        ){Handler(Looper.getMainLooper()).postDelayed({scroll(n+1)},400)}
    }

    fun extract(){
        val js="""
        (function(){
        let a=[];
        function add(x,s){
        if(!x)return;
        try{
        let u=new URL(String(x).trim(),document.baseURI).href;
        if(/^https?:\/\//i.test(u))a.push({url:u,source:s});
        }catch(e){}
        }
        document.querySelectorAll("a[href],area[href]").forEach(e=>add(e.getAttribute("href"),"DOM"));
        document.querySelectorAll("[data-url],[data-href],[data-link]").forEach(e=>{
        add(e.getAttribute("data-url"),"DOM");
        add(e.getAttribute("data-href"),"DOM");
        add(e.getAttribute("data-link"),"DOM");
        });
        let h=document.documentElement.outerHTML;
        let r=/https?:\/\/[^\s"'<>\\]+/gi,m;
        while((m=r.exec(h)))add(m[0],"SOURCE");
        return JSON.stringify(a);
        })()
        """.trimIndent()

        web.evaluateJavascript(js){result->
            try{
                val s=JSONTokener(result).nextValue() as String
                val a=JSONArray(s)
                val seen=links.map{it.url}.toMutableSet()
                for(i in 0 until a.length()){
                    val o=a.getJSONObject(i)
                    val u=o.getString("url").trimEnd('.',',',';',')',']')
                    if(seen.add(u))links.add(Link(u,o.optString("source","DOM")))
                }
                status="${links.size} unique links found"
            }catch(e){
                status="Extraction error: ${e.message}"
            }
            busy=false
        }
    }

    fun filtered():List<Link>{
        val host=try{URI(address).host.orEmpty().lowercase()}catch(e){""}
        return links.filter{
            (!https||it.url.startsWith("https://",true))&&
            (!external||try{URI(it.url).host.orEmpty().lowercase()!=host}catch(e){true})&&
            it.url.contains(search,true)
        }
    }

    fun copy(text:String){
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("Links",text))
        status="Copied"
    }

    fun save(u:Uri,csvMode:Boolean){
        try{
            contentResolver.openOutputStream(u)?.bufferedWriter()?.use{w->
                if(csvMode)w.write("URL,Source\n")
                filtered().forEach{
                    if(csvMode)w.write("\"${it.url.replace("\"","\"\"")}\",${it.source}\n")
                    else{w.write(it.url);w.newLine()}
                }
            }
            status="Export complete"
        }catch(e){status="Export failed"}
    }

    fun export(){
        exportCsv=true
        csv.launch("links.csv")
    }

    @Composable
    fun UI(){
        Scaffold(topBar={TopAppBar(title={Text("Link Extractor Pro")})}){p->
            Column(Modifier.fillMaxSize().padding(p)){
                TabRow(tab){
                    Tab(tab==0,{tab=0},{Text("Browser")})
                    Tab(tab==1,{tab=1},{Text("Links (${links.size})")})
                }
                if(tab==0)Browser() else Results()
            }
        }
    }

    @Composable
    fun Browser(){
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().padding(8.dp)){
                OutlinedTextField(
                    address,{address=it},
                    Modifier.weight(1f),
                    singleLine=true,
                    label={Text("Web URL")}
                )
                Spacer(Modifier.width(6.dp))
                Button({web.loadUrl(address)}){Text("Go")}
            }
            Row(Modifier.padding(horizontal=8.dp)){
                Switch(deep,{deep=it})
                Text("Deep scan",Modifier.padding(8.dp))
            }
            Text(status,Modifier.padding(8.dp))
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView({web},Modifier.fillMaxSize())
        }
    }

    @Composable
    fun Results(){
        val list=filtered()
        Column(Modifier.fillMaxSize().padding(8.dp)){
            OutlinedTextField(
                search,{search=it},
                Modifier.fillMaxWidth(),
                singleLine=true,
                label={Text("Search links")}
            )
            Row{
                Checkbox(https,{https=it})
                Text("HTTPS")
                Checkbox(external,{external=it})
                Text("External")
            }
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
                Button({copy(list.joinToString("\n"){it.url})}){Text("Copy All")}
                OutlinedButton({txt.launch("links.txt")}){Text("TXT")}
                OutlinedButton({export()}){Text("CSV")}
                OutlinedButton({links.clear()}){Text("Clear")}
            }
            OutlinedButton({
                tab=0
                scan()
            },enabled=!busy){Text("Scan Again")}
            Text("${list.size} links",Modifier.padding(6.dp))
            LazyColumn{
                items(list,key={it.url}){x->
                    Card(Modifier.fillMaxWidth().padding(3.dp)){
                        Column(Modifier.padding(8.dp)){
                            Text(x.url)
                            Text(x.source)
                            Row{
                                OutlinedButton({
                                    web.loadUrl(x.url);tab=0
                                }){Text("Open")}
                                Spacer(Modifier.width(5.dp))
                                OutlinedButton({copy(x.url)}){Text("Copy")}
                            }
                        }
                    }
                }
            }
        }
    }
}
