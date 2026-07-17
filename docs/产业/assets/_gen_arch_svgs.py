# -*- coding: utf-8 -*-
from pathlib import Path

OUT = Path(__file__).resolve().parent

# 6:4 redraw (not scale): left ~720 compact gaps; right ~480 text-fit nodes
# Bottom row 结束/数据获取/定时调度/代理IP: keep 60px box-gap (center step 180)

SUBSCRIBE = r'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 720 640" width="720" height="640" role="img" aria-label="订阅平台外网采集">
  <defs>
    <marker id="arr" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#64748B"/>
    </marker>
    <marker id="arr-c" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#0891b2"/>
    </marker>
    <marker id="arr-d" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#94a3b8"/>
    </marker>
  </defs>
  <style>
    .title { font: 700 14px/1.2 "trebuchet ms",verdana,arial,sans-serif; fill: #1e40af; }
    .layer-t { font: 700 12px/1.2 "trebuchet ms",verdana,arial,sans-serif; fill: #1e40af; }
    .layer-t.eng { fill: #0e7490; }
    .layer-t.store { fill: #15803d; }
    .nt { font: 700 12px/1.15 "trebuchet ms",verdana,arial,sans-serif; fill: #1e40af; }
    .nd { font: 500 10.5px/1.15 "trebuchet ms",verdana,arial,sans-serif; fill: #334155; }
    .edge { font: 600 10.5px/1 "trebuchet ms",verdana,arial,sans-serif; fill: #64748B; }
    .n { fill: #EEF5FF; stroke: #1d4ed8; stroke-width: 1.3; }
    .n-eng { fill: #ECFEFF; stroke: #0891b2; stroke-width: 1.3; }
    .n-mm { fill: #F3E8FF; stroke: #a855f7; stroke-width: 1.7; }
    .n-end { fill: #DCFCE7; stroke: #16a34a; stroke-width: 1.5; }
    .sg-cfg { fill: #EEF5FF; stroke: #1d4ed8; stroke-width: 1.3; }
    .sg-eng { fill: #F0F9FF; stroke: #0891b2; stroke-width: 1.3; }
    .sg-store { fill: #F0FDF4; stroke: #16a34a; stroke-width: 1.3; }
    .ln { stroke: #64748B; stroke-width: 1.3; fill: none; marker-end: url(#arr); }
    .ln-c { stroke: #0891b2; stroke-width: 1.45; fill: none; marker-end: url(#arr-c); }
    .ln-d { stroke: #94a3b8; stroke-width: 1.2; fill: none; stroke-dasharray: 4 3; marker-end: url(#arr-d); }
  </style>

  <text x="360" y="16" text-anchor="middle" class="title">订阅平台 · 外网采集</text>

  <!-- 配置层：贴边、节点间距收紧 -->
  <rect class="sg-cfg" x="8" y="24" width="704" height="70" rx="4"/>
  <text x="18" y="40" class="layer-t">① 配置层</text>
  <g transform="translate(18,46)"><rect class="n" width="210" height="40" rx="3"/><text class="nt" x="105" y="16" text-anchor="middle">采集任务配置</text><text class="nd" x="105" y="31" text-anchor="middle">频率·目标站点</text></g>
  <g transform="translate(248,46)"><rect class="n" width="210" height="40" rx="3"/><text class="nt" x="105" y="16" text-anchor="middle">配置同步</text><text class="nd" x="105" y="31" text-anchor="middle">接收星云下发</text></g>
  <g transform="translate(478,46)"><rect class="n" width="210" height="40" rx="3"/><text class="nt" x="105" y="16" text-anchor="middle">执行日志</text><text class="nd" x="105" y="31" text-anchor="middle">采集·验证状态</text></g>

  <!-- 配置→引擎：加长连线 -->
  <path class="ln-c" d="M360,94 L360,126"/>

  <rect class="sg-eng" x="8" y="132" width="704" height="348" rx="4"/>
  <text x="18" y="148" class="layer-t eng">② 引擎层 · 采集</text>

  <!-- 主链均匀：开始(c76) — 手动指定(c348) — AI脚本生成(c620)；子链均匀：AI检索(c257) — 渠道增强(c439) -->
  <g transform="translate(20,166)"><rect class="n-end" width="112" height="42" rx="21"/><text class="nt" x="56" y="16" text-anchor="middle" fill="#15803d">开始</text><text class="nd" x="56" y="31" text-anchor="middle" fill="#166534">数据源发现</text></g>
  <g transform="translate(207,164)"><rect class="n-mm" width="100" height="36" rx="3"/><text class="nt" x="50" y="14" text-anchor="middle" fill="#6b21a8">AI 检索</text><text class="nd" x="50" y="28" text-anchor="middle" fill="#6b21a8">联网找源</text></g>
  <g transform="translate(389,164)"><rect class="n-eng" width="100" height="36" rx="3"/><text class="nt" x="50" y="14" text-anchor="middle" fill="#0e7490">渠道增强</text><text class="nd" x="50" y="28" text-anchor="middle">源库扩充</text></g>

  <g transform="translate(294,218)"><rect class="n-eng" width="108" height="42" rx="3"/><text class="nt" x="54" y="16" text-anchor="middle" fill="#0e7490">手动指定</text><text class="nd" x="54" y="31" text-anchor="middle">管理员确认源</text></g>
  <!-- 右列等距：AI脚本生成 y=218 → 沙箱验证 y=298 → 代理IP y=378（节点高42，间距38） -->
  <g transform="translate(560,218)"><rect class="n-mm" width="120" height="42" rx="3"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#6b21a8">AI 脚本生成</text><text class="nd" x="60" y="31" text-anchor="middle" fill="#6b21a8">Python 爬虫</text></g>

  <path class="ln" d="M132,182 L205,182"/>
  <path class="ln" d="M307,182 L387,182"/>
  <path class="ln" d="M489,182 L620,182 L620,218"/>

  <path class="ln" d="M76,208 L76,239 L292,239"/>
  <path class="ln" d="M402,239 L558,239"/>

  <g transform="translate(560,298)"><rect class="n-eng" width="120" height="42" rx="3"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#0e7490">沙箱验证</text><text class="nd" x="60" y="31" text-anchor="middle">结构化落库</text></g>
  <path class="ln" d="M620,260 L620,296"/>

  <!-- 底行与代理IP同高；间隔保持 60px（节点宽120，步进180） -->
  <g transform="translate(560,378)"><rect class="n-eng" width="120" height="42" rx="3"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#0e7490">代理IP</text><text class="nd" x="60" y="31" text-anchor="middle">取IP/失败换IP</text></g>
  <g transform="translate(380,378)"><rect class="n-eng" width="120" height="42" rx="3"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#0e7490">定时调度</text><text class="nd" x="60" y="31" text-anchor="middle">并行执行脚本</text></g>
  <g transform="translate(200,378)"><rect class="n-eng" width="120" height="42" rx="3"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#0e7490">数据获取</text><text class="nd" x="60" y="31" text-anchor="middle">执行采集脚本</text></g>
  <g transform="translate(20,378)"><rect class="n-end" width="120" height="42" rx="21"/><text class="nt" x="60" y="16" text-anchor="middle" fill="#15803d">结束</text><text class="nd" x="60" y="31" text-anchor="middle" fill="#166534">数据落表</text></g>

  <path class="ln" d="M620,340 L620,376"/>
  <path class="ln" d="M560,399 L502,399"/>
  <path class="ln" d="M380,399 L322,399"/><text class="edge" x="328" y="391">访问成功</text>
  <path class="ln" d="M200,399 L142,399"/><text class="edge" x="148" y="391">成功</text>

  <path class="ln-d" d="M440,420 L440,438 L620,438 L620,420"/><text class="edge" x="470" y="434">访问被拒·换IP</text>

  <!-- 失败重试：底边绕行 → 右缘上行 -->
  <path class="ln-d" d="M260,420 L260,458 L700,458 L700,239 L682,239"/>
  <text class="edge" x="400" y="452">失败重试</text>

  <!-- 引擎→存储：加长连线 -->
  <path class="ln-c" d="M360,480 L360,512"/><text class="edge" x="368" y="498" fill="#0891b2">写</text>

  <rect class="sg-store" x="8" y="518" width="704" height="110" rx="4"/>
  <text x="18" y="536" class="layer-t store">③ 存储层 · PostgreSQL</text>
  <ellipse cx="360" cy="566" rx="100" ry="10" fill="#ECFDF5" stroke="#16a34a" stroke-width="1.4"/>
  <ellipse cx="360" cy="560" rx="100" ry="10" fill="#ECFDF5" stroke="#16a34a" stroke-width="1.4"/>
  <rect x="260" y="560" width="200" height="46" fill="#ECFDF5" stroke="none"/>
  <path d="M260,560 L260,606" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <path d="M460,560 L460,606" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <path d="M260,606 L460,606" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <text class="nt" x="360" y="582" text-anchor="middle" fill="#15803d" style="font-size:13px">PostgreSQL</text>
  <text class="nd" x="360" y="598" text-anchor="middle">原始数据 · 脚本 · 执行日志</text>
</svg>
'''

NEBULA = r'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 480 430" width="480" height="430" role="img" aria-label="星云平台">
  <defs>
    <marker id="arrR" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#64748B"/>
    </marker>
    <marker id="arrRc" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#0891b2"/>
    </marker>
    <marker id="arrRa" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="7" markerHeight="7" orient="auto">
      <path d="M2,2 L10,6 L2,10 Z" fill="#d97706"/>
    </marker>
  </defs>
  <style>
    .title { font: 700 13px/1.2 "trebuchet ms",verdana,arial,sans-serif; fill: #0e7490; }
    .layer-t { font: 700 11.5px/1.2 "trebuchet ms",verdana,arial,sans-serif; fill: #1e40af; }
    .layer-t.eng { fill: #b45309; }
    .layer-t.store { fill: #15803d; }
    .nt { font: 700 11.5px/1.15 "trebuchet ms",verdana,arial,sans-serif; fill: #1e40af; }
    .nd { font: 500 10px/1.15 "trebuchet ms",verdana,arial,sans-serif; fill: #334155; }
    .edge { font: 600 10px/1 "trebuchet ms",verdana,arial,sans-serif; fill: #64748B; }
    .n { fill: #EEF5FF; stroke: #1d4ed8; stroke-width: 1.3; }
    .n-push { fill: #FFF7ED; stroke: #d97706; stroke-width: 1.3; }
    .sg-cfg { fill: #EEF5FF; stroke: #1d4ed8; stroke-width: 1.3; }
    .sg-eng { fill: #FFFBEB; stroke: #d97706; stroke-width: 1.3; }
    .sg-store { fill: #F0FDF4; stroke: #16a34a; stroke-width: 1.3; }
    .ln { stroke: #64748B; stroke-width: 1.3; fill: none; marker-end: url(#arrR); }
    .ln-c { stroke: #0891b2; stroke-width: 1.45; fill: none; marker-end: url(#arrRc); }
    .ln-a { stroke: #d97706; stroke-width: 1.3; fill: none; marker-end: url(#arrRa); }
  </style>

  <text x="240" y="16" text-anchor="middle" class="title">星云平台 · 结构化与推送</text>

  <!-- 配置层：按文字收紧 -->
  <rect class="sg-cfg" x="8" y="24" width="464" height="78" rx="4"/>
  <text x="16" y="40" class="layer-t">① 配置层</text>
  <g transform="translate(14,46)"><rect class="n" width="92" height="48" rx="3"/><text class="nt" x="46" y="18" text-anchor="middle">产业配置</text><text class="nd" x="46" y="34" text-anchor="middle">煤炭·纺织·水产</text></g>
  <g transform="translate(116,46)"><rect class="n" width="92" height="48" rx="3"/><text class="nt" x="46" y="18" text-anchor="middle">订阅管理</text><text class="nd" x="46" y="34" text-anchor="middle">机构·关键字</text></g>
  <g transform="translate(218,46)"><rect class="n" width="124" height="48" rx="3"/><text class="nt" x="62" y="18" text-anchor="middle">邮件规则配置</text><text class="nd" x="62" y="34" text-anchor="middle">推送时间·收件人</text></g>
  <g transform="translate(352,46)"><rect class="n" width="108" height="48" rx="3"/><text class="nt" x="54" y="18" text-anchor="middle">执行日志</text><text class="nd" x="54" y="34" text-anchor="middle">采集·验证状态</text></g>

  <!-- 配置→引擎：加长连线 -->
  <path class="ln-c" d="M240,102 L240,134"/>

  <!-- 引擎层：三节点水平居中（左右边距各 40） -->
  <rect class="sg-eng" x="8" y="140" width="464" height="112" rx="4"/>
  <text x="16" y="158" class="layer-t eng">② 引擎层 · 推送</text>
  <g transform="translate(48,170)"><rect class="n-push" width="108" height="64" rx="3"/><text class="nt" x="54" y="24" text-anchor="middle" fill="#b45309">定时触发</text><text class="nd" x="54" y="42" text-anchor="middle">每日批次调度</text></g>
  <path class="ln" d="M158,202 L174,202"/>
  <g transform="translate(176,170)"><rect class="n-push" width="128" height="64" rx="3"/><text class="nt" x="64" y="24" text-anchor="middle" fill="#b45309">订阅匹配检索</text><text class="nd" x="64" y="42" text-anchor="middle">机构·产业·关键字</text></g>
  <path class="ln" d="M306,202 L322,202"/>
  <g transform="translate(324,170)"><rect class="n-push" width="108" height="64" rx="3"/><text class="nt" x="54" y="24" text-anchor="middle" fill="#b45309">邮件发送</text><text class="nd" x="54" y="42" text-anchor="middle">产业早报</text></g>

  <!-- 引擎→存储：读写箭头加长并相对中线对称 -->
  <path class="ln-a" d="M260,252 L260,284"/><text class="edge" x="268" y="270" fill="#d97706">写</text>
  <path class="ln-a" d="M220,284 L220,252"/><text class="edge" x="188" y="270" fill="#d97706">读</text>

  <rect class="sg-store" x="8" y="290" width="464" height="128" rx="4"/>
  <text x="16" y="308" class="layer-t store">③ 存储层 · PostgreSQL</text>
  <ellipse cx="240" cy="344" rx="118" ry="11" fill="#ECFDF5" stroke="#16a34a" stroke-width="1.4"/>
  <ellipse cx="240" cy="338" rx="118" ry="11" fill="#ECFDF5" stroke="#16a34a" stroke-width="1.4"/>
  <rect x="122" y="338" width="236" height="52" fill="#ECFDF5" stroke="none"/>
  <path d="M122,338 L122,390" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <path d="M358,338 L358,390" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <path d="M122,390 L358,390" stroke="#16a34a" stroke-width="1.4" fill="none"/>
  <text class="nt" x="240" y="362" text-anchor="middle" fill="#15803d" style="font-size:13px">PostgreSQL</text>
  <text class="nd" x="240" y="380" text-anchor="middle">原始数据 · 结构化情报 · 执行日志</text>
</svg>
'''

(OUT / "arch-subscribe.svg").write_text(SUBSCRIBE, encoding="utf-8")
(OUT / "arch-nebula.svg").write_text(NEBULA, encoding="utf-8")
print("ok")
