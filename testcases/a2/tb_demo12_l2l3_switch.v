`timescale 1ns/1ps
// demo12（L2/L3 交换机 v4：可选 OpaqueTag + 0..4 层 **802.1Q VLAN**）真实 TB。
//
// 用例按**VLAN 层数**组织，覆盖"可选"的全部边界：
//   ① 0 层 + L2  ⇒ 无可剥，报文不变        ⑤ 0 层 + L3  ⇒ 打 1 层（UPLINK_VID）
//   ② 1 层 + L2  ⇒ 剥光，剩 0 层            ⑥ OpaqueTag + 1 层 + L2 ⇒ 剥光，otag 指向 payload
//   ③ 2 层 + L2  ⇒ 剥外层，剩 1 层（内层顶上）⑦ 0 层 + L3(ttl=1) ⇒ ttl=0 且仍打 1 层
//   ④ 4 层 + L3  ⇒ 满层饱和保护：不再加层    ⑧ 1 层 + 泛洪 ⇒ 不编辑
//
// 与骨架 TB 的区别：
//  1. 两张 runtime 表外置，TB 充当表模块 mock，且**按 key 应答**（否则命中与否与
//     被测逻辑无关、测试永远绿）：看到 key_vld 后按 latency 契约（mac 1-4 拍 /
//     route 2-8 拍）延迟回 rsp 并保持一个窗口（valid_data 无背压：早到会丢、
//     晚到只是慢）。
//  2. 期望值独立计算：校验和用 RFC 1624 增量公式在 TB 内算（不抄运行结果）。
//  3. 统计断言与索引无关：只判「非零 32 位字个数 + 总和」，不猜 Register/Counter
//     的元素排列顺序。
//  4. 断言名用 ASCII：verilator 的 `%0s` 打印中文会产出非法 UTF-8（日志会被截坏）。
//
// ⚠️ 发包协议：pkt_in_vld 只挂**一拍**（parser 在相位 0 见 vld 即收包，
//    挂着不放会让它在转完一圈回 ph0 时把同一包再收一遍 —— 曾踩过）。
//
// 位口径（pkt-window 640；header 起点字节对齐、内部字段位紧凑）：
//   pkt_in  640 = eth112 | otag48 | vlan0..3 32×4 | etype16 | ipv4 160 | udp64 | 余量
//   pkt_out 698 = eth113 | otag49 | vlan0..3 33×4 | etype17 | ipv4 161 | udp65 | tcp161
module tb_demo12_l2l3_switch;
  reg clk = 0;
  always #5 clk = ~clk;
  reg rst = 1;

  reg [639:0] pkt_in = 0;
  reg         pkt_in_vld = 0;
  reg [5:0]   tbl_mac_table_rsp = 0;
  reg         tbl_mac_table_rsp_vld = 0;
  reg [101:0] tbl_route_table_rsp = 0;
  reg         tbl_route_table_rsp_vld = 0;
  reg         pkt_out_rdy = 1;

  wire [255:0] ex_portBytes;
  wire         ex_portBytes_vld;
  wire [127:0] ex_fwdCnt;
  wire         ex_fwdCnt_vld;
  wire [59:0]  tbl_mac_table_key;
  wire         tbl_mac_table_key_vld;
  wire [39:0]  tbl_route_table_key;
  wire         tbl_route_table_key_vld;
  wire [697:0] pkt_out;
  wire         pkt_out_vld;

  Ingress_pipeline dut (
    .clk(clk), .rst(rst),
    .pkt_in(pkt_in), .pkt_in_vld(pkt_in_vld),
    .tbl_mac_table_rsp(tbl_mac_table_rsp), .tbl_mac_table_rsp_vld(tbl_mac_table_rsp_vld),
    .tbl_route_table_rsp(tbl_route_table_rsp), .tbl_route_table_rsp_vld(tbl_route_table_rsp_vld),
    .pkt_out_rdy(pkt_out_rdy),
    .ex_portBytes(ex_portBytes), .ex_portBytes_vld(ex_portBytes_vld),
    .ex_fwdCnt(ex_fwdCnt), .ex_fwdCnt_vld(ex_fwdCnt_vld),
    .tbl_mac_table_key(tbl_mac_table_key), .tbl_mac_table_key_vld(tbl_mac_table_key_vld),
    .tbl_route_table_key(tbl_route_table_key), .tbl_route_table_key_vld(tbl_route_table_key_vld),
    .pkt_out(pkt_out), .pkt_out_vld(pkt_out_vld)
  );

  // ---------------------------------------------------------------- 表 mock
  reg        mac_hit = 0;  reg [3:0] mac_port = 0;
  reg        rt_hit  = 0;  reg [3:0] rt_port = 0;
  reg [47:0] rt_nh   = 0;  reg [47:0] rt_src = 0;
  reg [59:0] exp_mac_key = 0;   // 用例下发的 mac 表项：目的 MAC + 内层 VID
  reg [39:0] exp_rt_key  = 0;   // 用例下发的路由表项：isL3 + 目的 IP
  wire       mac_eff = mac_hit && (cap_mac_key == exp_mac_key);
  wire       rt_eff  = rt_hit  && (cap_rt_key  == exp_rt_key);

  always @(*) begin
    tbl_mac_table_rsp   = {mac_eff, ~mac_eff, mac_port};             // hit|actId|args(4)
    tbl_route_table_rsp = {rt_eff, ~rt_eff, rt_port, rt_nh, rt_src}; // hit|actId|port|nh|src
  end

  reg [3:0] mac_cnt = 0, rt_cnt = 0;
  always @(posedge clk) begin
    if (rst) begin
      mac_cnt <= 0; rt_cnt <= 0;
    end else begin
      if (tbl_mac_table_key_vld)   mac_cnt <= 4'd1;
      else if (mac_cnt != 0)       mac_cnt <= (mac_cnt == 4'd12) ? 4'd0 : mac_cnt + 4'd1;
      if (tbl_route_table_key_vld) rt_cnt <= 4'd1;
      else if (rt_cnt != 0)        rt_cnt <= (rt_cnt == 4'd12) ? 4'd0 : rt_cnt + 4'd1;
    end
  end
  always @(*) begin
    tbl_mac_table_rsp_vld   = (mac_cnt >= 4'd3);   // 发 key 后 2 拍起保持（契约 1-4）
    tbl_route_table_rsp_vld = (rt_cnt  >= 4'd4);   // 3 拍起（契约 2-8）
  end

  // ------------------------------------------------------------ 常量与期望值
  localparam [15:0] ET_IPV4   = 16'h0800;
  localparam [15:0] TPID_VLAN = 16'h8100;
  localparam [15:0] TPID_OTAG = 16'h8200;
  localparam [47:0] SWITCH_MAC = 48'h0200_0000_0001;
  localparam [47:0] OLD_DST    = 48'h0011_2233_4455;
  localparam [47:0] OLD_SRC    = 48'h00aa_bbcc_ddee;
  localparam [47:0] NH_MAC_3   = 48'h00de_adbe_ef01;
  localparam [31:0] DIP_3      = 32'h0a00_0003;
  localparam [15:0] IPLEN      = 16'd64;
  localparam [11:0] UPLINK_VID = 12'd100;
  localparam [31:0] NO_VLAN    = 32'h0;
  localparam [47:0] NO_OTAG    = 48'h0;

  function [15:0] rfc1624;                 // HC' = ~(~HC + ~m + m')，16 位回绕
    input [15:0] hc, m_old, m_new;
    begin
      rfc1624 = ~(~hc + ~m_old + m_new);
    end
  endfunction

  function [31:0] vlan_tag;                // 802.1Q：TPID(16) + PCP(3) + DEI(1) + VID(12)
    input [2:0] pcp; input dei; input [11:0] vid;
    begin
      vlan_tag = {TPID_VLAN, pcp, dei, vid};
    end
  endfunction

  function [47:0] otag_tag;                // OpaqueTag（自定义）：tpid + nextType + data
    input [15:0] next_type; input [15:0] data;
    begin
      otag_tag = {TPID_OTAG, next_type, data};
    end
  endfunction

  function [639:0] mk_pkt;                 // 固定槽位报文（不存在的槽位填 0）
    input [47:0] dmac, smac; input [15:0] etype;
    input [47:0] otag; input [31:0] v0, v1, v2, v3; input [15:0] etype_after;
    input [15:0] csum; input [7:0] ttl; input [31:0] sip, dip;
    begin
      mk_pkt = {dmac, smac, etype, otag, v0, v1, v2, v3, etype_after,
                4'h4, 4'h5, 8'h00, IPLEN, 16'h0000, 3'h0, 13'h0, ttl, 8'd17, csum, sip, dip,
                64'h0,      // UDP 槽（protocol=17 ⇒ 解析）
                112'h0};    // 窗口余量（TCP 槽与 UDP 同偏移，本例未解析）
    end
  endfunction

  // 统计断言：不猜元素排列，只数非零 32 位字与求和
  function [3:0] nz256; input [255:0] v; integer i;
    begin nz256 = 0; for (i = 0; i < 8; i = i + 1) if (v[32*i +: 32] != 0) nz256 = nz256 + 1; end
  endfunction
  function [63:0] sum256; input [255:0] v; integer i;
    begin sum256 = 0; for (i = 0; i < 8; i = i + 1) sum256 = sum256 + v[32*i +: 32]; end
  endfunction
  function [3:0] nz128; input [127:0] v; integer i;
    begin nz128 = 0; for (i = 0; i < 4; i = i + 1) if (v[32*i +: 32] != 0) nz128 = nz128 + 1; end
  endfunction

  integer errors = 0;
  integer cyc;

  task chk;
    input [8*40:1] name; input [63:0] got, want;
    begin
      if (got === want) $display("  [ok] %0s = %h", name, got);
      else begin
        $display("  [FAIL] %0s: got %h, want %h", name, got, want);
        errors = errors + 1;
      end
    end
  endtask

  task do_reset;
    begin
      rst = 1;
      repeat (3) @(posedge clk);
      @(negedge clk); rst = 0;
      repeat (2) @(posedge clk);
    end
  endtask

  task send;
    input [639:0] p;
    begin
      @(negedge clk); pkt_in = p; pkt_in_vld = 1;
      @(negedge clk); pkt_in_vld = 0;   // 一拍撤 vld
      cyc = 0;
      while (cyc < 300 && !pkt_out_vld) begin @(negedge clk); cyc = cyc + 1; end
      if (!pkt_out_vld) begin
        $display("  [FAIL] wait pkt_out_vld timeout");
        errors = errors + 1;
      end
    end
  endtask

  reg [255:0] cap_portBytes = 0;
  reg [127:0] cap_fwdCnt = 0;
  reg [59:0]  cap_mac_key = 0;
  reg [39:0]  cap_rt_key  = 0;
  always @(posedge clk) begin
    if (rst) begin
      cap_portBytes <= 0; cap_fwdCnt <= 0; cap_mac_key <= 0; cap_rt_key <= 0;
    end else begin
      if (ex_portBytes_vld)        cap_portBytes <= ex_portBytes;
      if (ex_fwdCnt_vld)           cap_fwdCnt    <= ex_fwdCnt;
      if (tbl_mac_table_key_vld)   cap_mac_key   <= tbl_mac_table_key;
      if (tbl_route_table_key_vld) cap_rt_key    <= tbl_route_table_key;
    end
  end

  // -------------------------------------------------------------------- 用例
  initial begin
    $dumpfile("tb_demo12_l2l3_switch.fst");
    $dumpvars(1, tb_demo12_l2l3_switch);
    do_reset;

    // ① 0 层 VLAN + L2 命中 ⇒ 无可剥（doPop=0），报文原样
    $display("[case 1] 0 VLAN + L2 hit  => nothing to pop");
    mac_hit = 1; mac_port = 4'd1; rt_hit = 0;
    exp_mac_key = {OLD_DST, 12'd0}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(ET_IPV4), .otag(NO_OTAG),
                .v0(NO_VLAN), .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN),
                .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key(dst+innerVid)", cap_mac_key, {OLD_DST, 12'd0});
    chk("ethType unchanged",     pkt_out[600:585], ET_IPV4);
    chk("vlan0 empty",           pkt_out[534:519], 16'h0);
    chk("ttl",                   pkt_out[321:314], 8'd64);
    chk("portBytes sum",         sum256(cap_portBytes), IPLEN);
    chk("fwdCnt nonzero words",  nz128(cap_fwdCnt), 4'd1);

    // ② 1 层 VLAN + L2 命中 ⇒ 剥一层 ⇒ 出包 0 层，eth.etherType 回到链末端类型
    $display("[case 2] 1 VLAN + L2 hit  => pop to 0");
    do_reset;
    mac_hit = 1; mac_port = 4'd1; rt_hit = 0;
    exp_mac_key = {OLD_DST, 12'd10}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_OTAG),
                .v0(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd10))),
                .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN), .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key innerVid=10",   cap_mac_key, {OLD_DST, 12'd10});
    chk("ethType -> payload",    pkt_out[600:585], ET_IPV4);
    chk("vlan0 cleared",         pkt_out[535:503], {1'b1, 32'h0});
    chk("ttl",                   pkt_out[321:314], 8'd64);

    // ③ 2 层 VLAN + L2 命中 ⇒ 剥外层，内层顶上（vid 20 被剥，剩 21）
    $display("[case 3] 2 VLAN + L2 hit  => pop outer, inner up");
    do_reset;
    mac_hit = 1; mac_port = 4'd2; rt_hit = 0;
    exp_mac_key = {OLD_DST, 12'd21}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_OTAG),
                .v0(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd20))),
                .v1(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd21))),
                .v2(NO_VLAN), .v3(NO_VLAN), .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key innerVid=21",   cap_mac_key, {OLD_DST, 12'd21});
    chk("ethType still VLAN",    pkt_out[600:585], TPID_VLAN);
    chk("vlan0 = old vlan1",     pkt_out[535:503], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd21))});
    chk("vlan1 cleared",         pkt_out[502:470], {1'b1, 32'h0});

    // ④ 4 层 VLAN（满配）+ L3 命中 ⇒ 饱和保护：不再加层
    $display("[case 4] 4 VLAN + L3 hit  => saturation, no push");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    exp_rt_key = {8'h01, DIP_3}; exp_mac_key = 0;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_OTAG),
                .v0(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd1))),
                .v1(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd2))),
                .v2(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd3))),
                .v3(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd4))),
                .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(DIP_3)));
    chk("rt-key(isL3=1+dip)",    cap_rt_key, {8'h01, DIP_3});
    chk("vlan0 kept vid=1",      pkt_out[535:503], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd1))});
    chk("vlan3 kept vid=4",      pkt_out[436:404], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd4))});
    chk("dstMac rewritten",      pkt_out[696:649], NH_MAC_3);
    chk("ttl-1",                 pkt_out[321:314], 8'd63);
    chk("hdrCsum(rfc1624)",      pkt_out[305:290], rfc1624(16'h1234, 16'd64, 16'd63));

    // ⑤ 0 层 VLAN + L3 命中 ⇒ 打 1 层外层 VLAN（trunk 上联）
    $display("[case 5] 0 VLAN + L3 hit  => push 1 (UPLINK_VID)");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    exp_rt_key = {8'h01, DIP_3}; exp_mac_key = 0;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(ET_IPV4), .otag(NO_OTAG),
                .v0(NO_VLAN), .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN),
                .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(DIP_3)));
    chk("ethType -> VLAN",       pkt_out[600:585], TPID_VLAN);
    chk("vlan0 pushed",          pkt_out[535:503], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(UPLINK_VID))});
    chk("etype field kept",      pkt_out[402:387], ET_IPV4);
    chk("dstMac",                pkt_out[696:649], NH_MAC_3);
    chk("ttl-1",                 pkt_out[321:314], 8'd63);

    // ⑥ OpaqueTag + 1 层 VLAN + L2 ⇒ 剥光后 otag.nextType 指回 payload
    $display("[case 6] OpaqueTag + 1 VLAN + L2 => pop, otag -> payload");
    do_reset;
    mac_hit = 1; mac_port = 4'd3; rt_hit = 0;
    exp_mac_key = {OLD_DST, 12'd30}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_OTAG),
                .otag(otag_tag(.next_type(TPID_VLAN), .data(16'habcd))),
                .v0(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd30))),
                .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN), .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key innerVid=30",   cap_mac_key, {OLD_DST, 12'd30});
    chk("ethType still OTAG",    pkt_out[600:585], TPID_OTAG);
    chk("otag next -> payload",  pkt_out[567:552], ET_IPV4);
    chk("vlan0 cleared",         pkt_out[535:503], {1'b1, 32'h0});

    // ⑦ TTL 耗尽（0 层 + L3，ttl=1）⇒ ttl=0 + 仍打 1 层（编辑在 ttl_guard 之前）
    $display("[case 7] TTL exhaust (L3 ttl=1) => ttl=0 + push");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    exp_rt_key = {8'h01, DIP_3}; exp_mac_key = 0;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(ET_IPV4), .otag(NO_OTAG),
                .v0(NO_VLAN), .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN),
                .etype_after(ET_IPV4),
                .csum(16'h5678), .ttl(8'd1), .sip(32'h0a000001), .dip(DIP_3)));
    chk("ttl=0",                 pkt_out[321:314], 8'd0);
    chk("hdrCsum(rfc1624)",      pkt_out[305:290], rfc1624(16'h5678, 16'd1, 16'd0));
    chk("vlan0 pushed anyway",   pkt_out[535:503], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(UPLINK_VID))});
    chk("fwdCnt nonzero words",  nz128(cap_fwdCnt), 4'd2);   // L3 + DROP

    // ⑧ 泛洪（1 层 VLAN）⇒ 不编辑，层数与 VID 原样
    $display("[case 8] flood (1 VLAN) => no edit");
    do_reset;
    mac_hit = 0; rt_hit = 0; exp_mac_key = 0; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_OTAG),
                .v0(vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd40))),
                .v1(NO_VLAN), .v2(NO_VLAN), .v3(NO_VLAN), .etype_after(ET_IPV4),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("ethType unchanged",     pkt_out[600:585], TPID_VLAN);
    chk("vlan0 kept vid=40",     pkt_out[535:503], {1'b1, vlan_tag(.pcp(3'h0), .dei(1'h0), .vid(12'd40))});
    chk("ttl",                   pkt_out[321:314], 8'd64);
    chk("portBytes sum",         sum256(cap_portBytes), 64'd0);
    chk("fwdCnt nonzero words",  nz128(cap_fwdCnt), 4'd0);

    repeat (3) @(posedge clk);
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $finish;
  end

  initial begin
    #200000;
    $display("[FAIL] simulation timeout");
    $finish;
  end
endmodule
