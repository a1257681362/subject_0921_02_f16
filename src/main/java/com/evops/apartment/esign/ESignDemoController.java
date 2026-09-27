package com.evops.apartment.esign;

import com.evops.common.ApiResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

/**
 * 电子签章「平台侧」演示控制台（仅 mock 提供方可用）：
 *
 * - GET  /api/esign-demo/console                演示页（查看平台收到的签署流程、模拟租客签署）
 * - GET  /api/esign-demo/flows                  平台当前签署流程
 * - POST /api/esign-demo/flows/{flowNo}/complete  模拟租客在 H5 完成签署
 *
 * 默认 mock 平台在 signWaitMs 后也会自动完成签署；本页用于演示时手动掌控节奏。
 * 切 provider=http 后本接口返回提示（页面按钮不可用），真实平台签署由对方 H5 完成。
 */
@RestController
@RequestMapping("/api/esign-demo")
public class ESignDemoController {
    private final ObjectProvider<MockESignPlatformClient> mockClient;

    public ESignDemoController(ObjectProvider<MockESignPlatformClient> mockClient) {
        this.mockClient = mockClient;
    }

    @GetMapping("/flows")
    public ApiResponse<Object> flows() {
        MockESignPlatformClient client = mockClient.getIfAvailable();
        if (client == null) {
            return ApiResponse.fail("当前对接真实电子签章平台（provider=http），模拟流程列表不可用");
        }
        return ApiResponse.ok(client.listFlows());
    }

    @PostMapping("/flows/{flowNo}/complete")
    public ApiResponse<Object> complete(@PathVariable String flowNo) {
        MockESignPlatformClient client = mockClient.getIfAvailable();
        if (client == null) {
            return ApiResponse.fail("当前对接真实电子签章平台（provider=http），模拟签署不可用");
        }
        client.manualCompleteSign(flowNo);
        return ApiResponse.ok(Collections.singletonMap("flowNo", flowNo));
    }

    /** 自包含演示页（text/html），下周对接演示直接打开 /api/esign-demo/console。 */
    @GetMapping(value = "/console", produces = MediaType.TEXT_HTML_VALUE + ";charset=utf-8")
    public String console() {
        boolean mock = mockClient.getIfAvailable() != null;
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>电子签章平台 · 演示控制台</title><style>"
                + "body{font-family:-apple-system,'Microsoft YaHei',sans-serif;margin:0;background:#f4f6fa;color:#1f2937}"
                + ".bar{background:#1d4ed8;color:#fff;padding:14px 24px;font-size:18px;font-weight:600}"
                + ".wrap{max-width:900px;margin:24px auto;padding:0 16px}"
                + ".card{background:#fff;border-radius:10px;box-shadow:0 1px 4px rgba(0,0,0,.08);padding:18px 22px;margin-bottom:16px}"
                + "table{width:100%;border-collapse:collapse;font-size:14px}"
                + "th,td{text-align:left;padding:9px 8px;border-bottom:1px solid #eef0f4}"
                + "th{color:#6b7280;font-weight:500}"
                + ".tag{display:inline-block;padding:2px 10px;border-radius:12px;font-size:12px}"
                + ".SIGNING{background:#fef3c7;color:#92400e}.SIGNED{background:#dbeafe;color:#1e40af}"
                + ".SEALED{background:#e0e7ff;color:#3730a3}.FINISHED{background:#dcfce7;color:#166534}"
                + "button{background:#1d4ed8;color:#fff;border:0;border-radius:6px;padding:6px 14px;cursor:pointer}"
                + "button:disabled{background:#9ca3af;cursor:not-allowed}"
                + ".muted{color:#6b7280;font-size:13px;line-height:1.7}"
                + "code{background:#f1f5f9;padding:1px 6px;border-radius:4px}"
                + "</style></head><body>"
                + "<div class=\"bar\">电子签章平台 · 演示控制台（mock）</div><div class=\"wrap\">"
                + "<div class=\"card\"><div id=\"mode\"></div>"
                + "<p class=\"muted\">本页模拟<b>电子签章平台侧</b>：签约归档后台任务走到「等待租客签署」时，"
                + "平台会出现一条 SIGNING 流程；租客在签署 H5 签字（默认约 1.5 秒自动完成，"
                + "也可在下方手动点「完成签署」掌控节奏），EVOPS 后台随后自动盖章、回传文件、归档。<br>"
                + "业务侧任务进度请在 EVOPS 前端按 <code>GET /api/apartment/sign-archive/tasks/{taskNo}</code> 轮询。</p>"
                + "<p><button onclick=\"load()\">刷新流程</button> "
                + "<span class=\"muted\">每 3 秒自动刷新</span></p></div>"
                + "<div class=\"card\"><table><thead><tr>"
                + "<th>业务号</th><th>平台流水</th><th>签署人</th><th>状态</th><th>操作</th>"
                + "</tr></thead><tbody id=\"rows\"><tr><td colspan=5 class=muted>加载中…</td></tr></tbody></table></div>"
                + "</div><script>"
                + "var MOCK=" + mock + ";"
                + "function esc(s){return String(s==null?'':s).replace(/[&<>]/g,function(c){"
                + "return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}"
                + "function load(){if(!MOCK){document.getElementById('mode').innerHTML="
                + "'<p class=muted>当前 evops.esign.provider=http，对接真实平台，本页仅占位。</p>';"
                + "document.getElementById('rows').innerHTML='';return;}"
                + "fetch('/api/esign-demo/flows').then(function(r){return r.json();}).then(function(res){"
                + "if(!res.success){document.getElementById('rows').innerHTML="
                + "'<tr><td colspan=5 class=muted>'+esc(res.message)+'</td></tr>';return;}"
                + "var rows=(res.data||[]).map(function(f){var can=f.status==='SIGNING';"
                + "return '<tr><td>'+esc(f.bizNo)+'</td><td>'+esc(f.flowNo)+'</td><td>'+esc(f.signer)+'</td>'"
                + "+'<td><span class=\"tag '+f.status+'\">'+f.status+'</span></td><td>'"
                + "+(can?'<button onclick=completeSign(\"'+f.flowNo+'\")>租客完成签署</button>':'—')+'</td></tr>';});"
                + "document.getElementById('rows').innerHTML=rows.join('')||"
                + "'<tr><td colspan=5 class=muted>暂无流程，发起一个签约归档任务后此处会出现 SIGNING 记录</td></tr>';});}"
                + "function completeSign(no){fetch('/api/esign-demo/flows/'+encodeURIComponent(no)+'/complete',"
                + "{method:'POST'}).then(function(){setTimeout(load,200);});}"
                + "load();setInterval(load,3000);"
                + "</script></body></html>";
    }
}
