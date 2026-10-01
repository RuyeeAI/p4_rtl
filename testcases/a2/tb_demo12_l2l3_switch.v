`timescale 1ns/1ps
// demo12（L2/L3 交换机 v4：可选 OpaqueTag + 最多 4 层 VLAN）真实 TB。
//
// 覆盖五类场景：① L2 转发 + 剥外层 VLAN（access） ② L3 路由 + 打外层 VLAN（trunk 上联）
// ③ OpaqueTag + 双层 VLAN 的封装链解析（内层 VID 参与查表）④ TTL 耗尽 ⑤ 泛洪（不编辑）
//
// 与骨架 TB 的区别：
//  1. 两张 runtime 表外置，TB 充当表模块 mock：看到 key_vld 后按 latency 契约
//     （mac 1-4 拍 / route 2-8 拍）延迟回 rsp 并保持一个窗口（valid_data 无背压：
//     proc 会停在收 rsp 的相位等，早到会丢、晚到只是慢）；rsp 由用例设定（= 控制面表项）。
//  2. 期望值独立计算：校验和用 RFC 1624 增量公式在 TB 内算（不抄运行结果）。
//  3. 统计断言与索引无关：只判「非零 32 位字个数 + 总和」，不猜 Register/Counter
//     的元素排列顺序。
//
// ⚠️ 发包协议：pkt_in_vld 只挂**一拍**（parser 在相位 0 见 vld 即收包，
//    挂着不放会让它在转完一圈回 ph0 时把同一包再收一遍 —— 曾踩过）。
//
// 位口径（pkt-window 800；偏移按**字段字节对齐**累加 —— 子集 M3 硬约束）：
//   pkt_in  800 = eth112 | otag64 | vlan0..3 64×4 | ipv4 176(字段按字节补齐) | udp64 | 余量
//   pkt_out 825 = eth113 | otag65 | vlan0..3 65×4 | ipv4 161 | udp65 | tcp161
//   每个可选槽位自带 tpid + nextType（子集不变长解析 ⇒ 存在性靠 tpid 判定）
module tb_demo12_l2l3_switch;
  reg clk = 0;
  always #5 clk = ~clk;
  reg rst = 1;

  reg [799:0] pkt_in = 0;
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
  wire [63:0]  tbl_mac_table_key;
  wire         tbl_mac_table_key_vld;
  wire [39:0]  tbl_route_table_key;
  wire         tbl_route_table_key_vld;
  wire [824:0] pkt_out;
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

  // ⚠️ mock 必须**按 key 应答**：否则"命中与否"与被测逻辑（innerVid / isL3 的
  //    计算）无关，TB 永远绿。这里只有 key 与用例预期的表项一致时才回 hit。
  reg [63:0] exp_mac_key = 0;              // 用例下发的 mac 表项（目的 MAC + 内层 VID）
  reg [39:0] exp_rt_key  = 0;              // 用例下发的路由表项（isL3 + 目的 IP）
  wire       mac_eff = mac_hit && (cap_mac_key == exp_mac_key);
  wire       rt_eff  = rt_hit  && (cap_rt_key  == exp_rt_key);

  always @(*) begin
    tbl_mac_table_rsp   = {mac_eff, ~mac_eff, mac_port};            // hit|actId|args(4)
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
  localparam [15:0] UPLINK_VID = 16'd100;
  localparam [63:0] NO_TAG     = 64'h0;

  function [15:0] rfc1624;                 // HC' = ~(~HC + ~m + m')，16 位回绕
    input [15:0] hc, m_old, m_new;
    begin
      rfc1624 = ~(~hc + ~m_old + m_new);
    end
  endfunction

  function [63:0] vlan_tag;                // 槽位：tpid | nextType | pcp | dei | vid
    input [15:0] next_type; input [7:0] pcp, dei; input [15:0] vid;
    begin
      vlan_tag = {TPID_VLAN, next_type, pcp, dei, vid};
    end
  endfunction

  function [63:0] otag_tag;                // 槽位：tpid | nextType | tagData
    input [15:0] next_type; input [31:0] data;
    begin
      otag_tag = {TPID_OTAG, next_type, data};
    end
  endfunction

  function [799:0] mk_pkt;                 // 固定槽位报文（不存在的槽位填 0）
    input [47:0] dmac, smac; input [15:0] etype;
    input [63:0] otag, v0, v1, v2, v3;
    input [15:0] csum; input [7:0] ttl; input [31:0] sip, dip;
    begin
      // 口径：header **起点**字节对齐（偏移 = 各 header 的 ceil 字节和），
      //       header **内部**字段位紧凑（不逐字段补齐）⇒ IPv4 只有 160 位，
      //       但下一个 header 要落在字节边界 ⇒ 这里补 16 位。
      mk_pkt = {dmac, smac, etype, otag, v0, v1, v2, v3,
                4'h4, 4'h5, 8'h00, IPLEN, 16'h0000, 3'h0, 13'h0, ttl, 8'd17, csum, sip, dip,
                16'h0,      // 补到字节边界（ipv4 占 22 字节）
                64'h0,      // UDP 槽（protocol=17 ⇒ 解析）
                128'h0};    // 窗口余量（TCP 槽与 UDP 同偏移，本例未解析）
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
    input [8*32:1] name; input [63:0] got, want;
    begin
      if (got === want) $display("  [ok] %0s = %h", name, got);
      else begin
        $display("  [FAIL] %0s: 实际 %h，期望 %h", name, got, want);
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
    input [799:0] p;
    begin
      @(negedge clk); pkt_in = p; pkt_in_vld = 1;
      @(negedge clk); pkt_in_vld = 0;   // 一拍撤 vld
      cyc = 0;
      while (cyc < 300 && !pkt_out_vld) begin @(negedge clk); cyc = cyc + 1; end
      if (!pkt_out_vld) begin
        $display("  [FAIL] 等待 pkt_out_vld 超时");
        errors = errors + 1;
      end
    end
  endtask

  reg [255:0] cap_portBytes = 0;
  reg [127:0] cap_fwdCnt = 0;
  reg [63:0]  cap_mac_key = 0;
  reg [39:0]  cap_rt_key  = 0;
  always @(posedge clk) begin
    if (rst) begin
      cap_portBytes <= 0; cap_fwdCnt <= 0; cap_mac_key <= 0; cap_rt_key <= 0;
    end else begin
      if (ex_portBytes_vld)       cap_portBytes <= ex_portBytes;
      if (ex_fwdCnt_vld)          cap_fwdCnt    <= ex_fwdCnt;
      if (tbl_mac_table_key_vld)  cap_mac_key   <= tbl_mac_table_key;
      if (tbl_route_table_key_vld) cap_rt_key   <= tbl_route_table_key;
    end
  end

  // -------------------------------------------------------------------- 用例
  initial begin
    $dumpfile("tb_demo12_l2l3_switch.fst");
    $dumpvars(1, tb_demo12_l2l3_switch);
    do_reset;

    // ① L2 转发 + 剥外层 VLAN（用户口 access 出方向不带 VLAN）
    $display("■ ① L2 转发（mac 命中 port1）+ 剥外层 VLAN");
    mac_hit = 1; mac_port = 4'd1; rt_hit = 0; exp_mac_key = {OLD_DST, 16'd10}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_TAG),
                .v0(vlan_tag(.next_type(ET_IPV4), .pcp(8'h0), .dei(8'h0), .vid(16'd10))),
                .v1(NO_TAG), .v2(NO_TAG), .v3(NO_TAG),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key(dst+innerVid)", cap_mac_key, {OLD_DST, 16'd10});
    chk("rt-key isL3=0 (L2 pkt)", cap_rt_key[39:32], 8'h00);
    chk("ipv4_v",   pkt_out[386],     1'h1);
    chk("dstMac",   pkt_out[823:776], OLD_DST);
    chk("ethType",  pkt_out[727:712], ET_IPV4);       // 剥 VLAN 后指回 IPv4
    chk("vlan0 popped (tpid=0)", pkt_out[645:630], 16'h0);
    chk("ttl",      pkt_out[321:314], 8'd64);         // 二层不改 TTL
    chk("hdrCsum",  pkt_out[305:290], 16'h1234);
    chk("portBytes nonzero words", nz256(cap_portBytes), 4'd1);
    chk("portBytes sum",     sum256(cap_portBytes), IPLEN);
    chk("fwdCnt nonzero words",    nz128(cap_fwdCnt), 4'd1);   // L2 计数 +1

    // ② L3 路由 + 打外层 VLAN（上联口 trunk 出方向带 UPLINK_VID）
    $display("■ ② L3 路由（route 命中 port0）+ 打外层 VLAN");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    exp_rt_key = {8'h01, DIP_3}; exp_mac_key = 0;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(ET_IPV4),
                .otag(NO_TAG), .v0(NO_TAG), .v1(NO_TAG), .v2(NO_TAG), .v3(NO_TAG),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(DIP_3)));
    chk("rt-key(isL3=1+dip)", cap_rt_key, {8'h01, DIP_3});
    chk("ethType",    pkt_out[727:712], TPID_VLAN);
    chk("vlan0 tpid", pkt_out[645:630], TPID_VLAN);
    chk("vlan0 next", pkt_out[629:614], ET_IPV4);
    chk("vlan0 vid",  pkt_out[597:582], UPLINK_VID);
    chk("dstMac",     pkt_out[823:776], NH_MAC_3);
    chk("srcMac",     pkt_out[775:728], SWITCH_MAC);
    chk("ttl",        pkt_out[321:314], 8'd63);
    chk("hdrCsum",    pkt_out[305:290], rfc1624(16'h1234, 16'd64, 16'd63));
    chk("fwdCnt nonzero words", nz128(cap_fwdCnt), 4'd1);   // L3 计数 +1

    // ③ OpaqueTag + 双层 VLAN：封装链解析（otag→vlan0→vlan1），内层 VID 参与查表
    $display("■ ③ OpaqueTag + 双层 VLAN（内层 VID 查表 + 剥外层）");
    do_reset;
    mac_hit = 1; mac_port = 4'd2; rt_hit = 0; exp_mac_key = {OLD_DST, 16'd21}; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_OTAG),
                .otag(otag_tag(.next_type(TPID_VLAN), .data(32'habcd_ef01))),
                .v0(vlan_tag(.next_type(TPID_VLAN), .pcp(8'h0), .dei(8'h0), .vid(16'd20))),
                .v1(vlan_tag(.next_type(ET_IPV4),   .pcp(8'h0), .dei(8'h0), .vid(16'd21))),
                .v2(NO_TAG), .v3(NO_TAG),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("mac-key(dst+innerVid)", cap_mac_key, {OLD_DST, 16'd21});
    chk("otag tpid",  pkt_out[710:695], TPID_OTAG);
    chk("otag next",  pkt_out[694:679], TPID_VLAN);    // 剥层后仍指向 VLAN
    chk("vlan0 tpid", pkt_out[645:630], TPID_VLAN);
    chk("vlan0 next", pkt_out[629:614], ET_IPV4);      // 原 vlan1 顶上
    chk("vlan0 vid",  pkt_out[597:582], 16'd21);
    chk("vlan1 cleared", pkt_out[580:565], 16'h0);
    chk("ethType unchanged (otag)", pkt_out[727:712], TPID_OTAG);
    chk("ttl",        pkt_out[321:314], 8'd64);

    // ④ TTL 耗尽：L3 命中 + ttl=1 ⇒ ttl=0、ttl_guard 丢弃（L3 与 DROP 各计一次）
    $display("■ ④ TTL 耗尽（L3 命中 ttl=1 ⇒ ttl=0 + drop，仍打外层 VLAN）");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    exp_rt_key = {8'h01, DIP_3}; exp_mac_key = 0;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(ET_IPV4),
                .otag(NO_TAG), .v0(NO_TAG), .v1(NO_TAG), .v2(NO_TAG), .v3(NO_TAG),
                .csum(16'h5678), .ttl(8'd1), .sip(32'h0a000001), .dip(DIP_3)));
    chk("ttl",        pkt_out[321:314], 8'd0);
    chk("hdrCsum",    pkt_out[305:290], rfc1624(16'h5678, 16'd1, 16'd0));
    chk("vlan0 vid",  pkt_out[597:582], UPLINK_VID);   // 编辑发生在 ttl_guard 之前
    chk("fwdCnt nonzero words", nz128(cap_fwdCnt), 4'd2);   // L3 + DROP

    // ⑤ 泛洪：两表都不命中 ⇒ 不编辑（既不打也不剥 VLAN）
    $display("■ ⑤ 泛洪（两表均未命中 ⇒ 报文原样）");
    do_reset;
    mac_hit = 0; rt_hit = 0; exp_mac_key = 0; exp_rt_key = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(TPID_VLAN), .otag(NO_TAG),
                .v0(vlan_tag(.next_type(ET_IPV4), .pcp(8'h0), .dei(8'h0), .vid(16'd30))),
                .v1(NO_TAG), .v2(NO_TAG), .v3(NO_TAG),
                .csum(16'h1234), .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("ethType",    pkt_out[727:712], TPID_VLAN);
    chk("vlan0 vid",  pkt_out[597:582], 16'd30);       // 原样保留
    chk("ttl",        pkt_out[321:314], 8'd64);
    chk("portBytes sum", sum256(cap_portBytes), 64'd0);
    chk("fwdCnt nonzero words", nz128(cap_fwdCnt), 4'd0);

    repeat (3) @(posedge clk);
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $finish;
  end

  initial begin
    #200000;
    $display("[FAIL] 仿真超时");
    $finish;
  end
endmodule
