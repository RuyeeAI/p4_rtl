`timescale 1ns/1ps
// demo12（L2/L3 交换机）真实 TB —— 覆盖 L2 转发 / L3 路由 / 泛洪 / TTL 耗尽四类场景。
//
// 与骨架 TB 的区别：
//  1. 两张 runtime 表（mac_table / route_table）**外置**，TB 充当表模块 mock：
//     看到 key_vld 后按 latency 契约（mac 1-4 拍 / route 2-8 拍）延迟回 rsp，
//     rsp 内容与命中与否由用例设定（模拟控制面下发的表项）。
//  2. 期望值**独立计算**：校验和用 RFC 1624 增量公式在 TB 内算（不抄运行结果）。
//  3. 统计断言与索引无关：只判「非零 32 位字个数 + 总和」，不猜 Register/Counter
//     的元素排列顺序。
//
// ⚠️ 发包协议：pkt_in_vld 只挂**一拍**（parser 在相位 0 见 vld 即收包，
//    挂着不放会让它在转完一圈回 ph0 时把同一包再收一遍 —— 曾踩过）。
//
// 位口径（与生成 Verilog 一致）：
//   pkt_in  512 = eth112(dst48/src48/etype16) + ipv4_160 + udp64 + tcp160 + pad16
//   pkt_out 500 = eth113 + ipv4_161 + udp65 + tcp161（每 header 前置 1 位 valid）
module tb_demo12_l2l3_switch;
  reg clk = 0;
  always #5 clk = ~clk;
  reg rst = 1;

  reg [511:0] pkt_in = 0;
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
  wire [47:0]  tbl_mac_table_key;
  wire         tbl_mac_table_key_vld;
  wire [39:0]  tbl_route_table_key;
  wire         tbl_route_table_key_vld;
  wire [499:0] pkt_out;
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
  // 命中与否由用例给出（= 控制面表项）；key 发出后延迟若干拍回 rsp 并保持一个
  // 窗口（valid_data 无背压：proc 会停在收 rsp 的相位等，早到会丢、晚到只是慢）。
  reg        mac_hit = 0;  reg [3:0] mac_port = 0;
  reg        rt_hit  = 0;  reg [3:0] rt_port = 0;
  reg [47:0] rt_nh   = 0;  reg [47:0] rt_src = 0;

  always @(*) begin
    tbl_mac_table_rsp   = {mac_hit, ~mac_hit, mac_port};            // hit|actId|args(4)
    tbl_route_table_rsp = {rt_hit, ~rt_hit, rt_port, rt_nh, rt_src}; // hit|actId|port|nh|src
  end

  reg [3:0] mac_cnt = 0, rt_cnt = 0;
  always @(posedge clk) begin
    if (rst) begin
      mac_cnt <= 0; rt_cnt <= 0;
    end else begin
      if (tbl_mac_table_key_vld)      mac_cnt <= 4'd1;
      else if (mac_cnt != 0)          mac_cnt <= (mac_cnt == 4'd12) ? 4'd0 : mac_cnt + 4'd1;
      if (tbl_route_table_key_vld)    rt_cnt <= 4'd1;
      else if (rt_cnt != 0)           rt_cnt <= (rt_cnt == 4'd12) ? 4'd0 : rt_cnt + 4'd1;
    end
  end
  always @(*) begin
    tbl_mac_table_rsp_vld   = (mac_cnt >= 4'd3);   // 发 key 后 2 拍起保持（契约 1-4）
    tbl_route_table_rsp_vld = (rt_cnt  >= 4'd4);   // 3 拍起（契约 2-8）
  end

  // ------------------------------------------------------------ 辅助与期望值
  localparam [47:0] SWITCH_MAC = 48'h0200_0000_0001;
  localparam [47:0] OLD_DST    = 48'h0011_2233_4455;
  localparam [47:0] OLD_SRC    = 48'h00aa_bbcc_ddee;
  localparam [47:0] NH_MAC_3   = 48'h00de_adbe_ef01;
  localparam [31:0] DIP_3      = 32'h0a00_0003;
  localparam [15:0] IPLEN      = 16'd64;

  function [15:0] rfc1624;                 // HC' = ~(~HC + ~m + m')，16 位回绕
    input [15:0] hc, m_old, m_new;
    begin
      rfc1624 = ~(~hc + ~m_old + m_new);
    end
  endfunction

  function [511:0] mk_pkt;                 // 以太网 + IPv4(UDP) 报文
    input [47:0] dmac, smac; input [15:0] etype, csum;
    input [7:0] ttl; input [31:0] sip, dip;
    begin
      mk_pkt = {dmac, smac, etype,
                4'h4, 4'h5, 8'h00, IPLEN, 16'h0000, 3'h0, 13'h0, ttl, 8'd17, csum, sip, dip,
                16'h0, 16'h0, 16'h0, 16'h0,   // UDP（protocol=17 ⇒ 解析）
                160'h0,                        // TCP 区（未解析 ⇒ valid=0）
                16'h0};                        // 补到 512
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
    input [511:0] p;
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
  always @(posedge clk) begin
    if (rst) begin cap_portBytes <= 0; cap_fwdCnt <= 0; end
    else begin
      if (ex_portBytes_vld) cap_portBytes <= ex_portBytes;
      if (ex_fwdCnt_vld)    cap_fwdCnt    <= ex_fwdCnt;
    end
  end

  // -------------------------------------------------------------------- 用例
  initial begin
    $dumpfile("tb_demo12_l2l3_switch.fst");
    $dumpvars(1, tb_demo12_l2l3_switch);
    do_reset;

    // ① L2 转发：目的 MAC 非交换机 MAC ⇒ isL3=0；mac_table 命中 port1，route 未命中
    $display("■ ① L2 转发（mac 命中 port1，L3 未命中）");
    mac_hit = 1; mac_port = 4'd1; rt_hit = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(16'h0800), .csum(16'h1234),
                .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("eth_v",     pkt_out[499],   1'h1);
    chk("ipv4_v",    pkt_out[386],   1'h1);
    chk("dstMac",    pkt_out[498:451], OLD_DST);     // L2 命中不改包
    chk("srcMac",    pkt_out[450:403], OLD_SRC);
    chk("ttl",       pkt_out[321:314], 8'd64);
    chk("hdrCsum",   pkt_out[305:290], 16'h1234);
    chk("portBytes 非零字数", nz256(cap_portBytes), 4'd1);
    chk("portBytes 合计",     sum256(cap_portBytes), IPLEN);
    chk("fwdCnt 非零字数",    nz128(cap_fwdCnt), 4'd1);   // L2 计数 +1

    // ② L3 路由：目的 MAC == 交换机 MAC ⇒ isL3=1；route 命中 port0（下一跳 NH_MAC_3）
    $display("■ ② L3 路由（route 命中 port0，改 MAC / 减 TTL / RFC1624 校验和）");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(16'h0800), .csum(16'h1234),
                .ttl(8'd64), .sip(32'h0a000001), .dip(DIP_3)));
    chk("dstMac",  pkt_out[498:451], NH_MAC_3);
    chk("srcMac",  pkt_out[450:403], SWITCH_MAC);
    chk("ttl",     pkt_out[321:314], 8'd63);
    chk("hdrCsum", pkt_out[305:290], rfc1624(16'h1234, 16'd64, 16'd63));
    chk("portBytes 非零字数", nz256(cap_portBytes), 4'd1);
    chk("fwdCnt 非零字数",    nz128(cap_fwdCnt), 4'd1);   // L3 计数 +1

    // ③ 泛洪：两表都未命中 ⇒ 不改包、不计字节、不计数
    $display("■ ③ 泛洪（两表均未命中）");
    do_reset;
    mac_hit = 0; rt_hit = 0;
    send(mk_pkt(.dmac(OLD_DST), .smac(OLD_SRC), .etype(16'h0800), .csum(16'h1234),
                .ttl(8'd64), .sip(32'h0a000001), .dip(32'h0a000002)));
    chk("dstMac",  pkt_out[498:451], OLD_DST);
    chk("srcMac",  pkt_out[450:403], OLD_SRC);
    chk("ttl",     pkt_out[321:314], 8'd64);
    chk("portBytes 合计", sum256(cap_portBytes), 64'd0);
    chk("fwdCnt 非零字数", nz128(cap_fwdCnt), 4'd0);

    // ④ TTL 耗尽：L3 命中 + ttl=1 ⇒ 减到 0、ttl_guard 丢弃（计数含 L3 与 DROP）
    $display("■ ④ TTL 耗尽（L3 命中 ttl=1 ⇒ ttl=0 + drop）");
    do_reset;
    mac_hit = 0; rt_hit = 1; rt_port = 4'd0; rt_nh = NH_MAC_3; rt_src = SWITCH_MAC;
    send(mk_pkt(.dmac(SWITCH_MAC), .smac(OLD_SRC), .etype(16'h0800), .csum(16'h5678),
                .ttl(8'd1), .sip(32'h0a000001), .dip(DIP_3)));
    chk("ttl",     pkt_out[321:314], 8'd0);
    chk("hdrCsum", pkt_out[305:290], rfc1624(16'h5678, 16'd1, 16'd0));
    chk("dstMac",  pkt_out[498:451], NH_MAC_3);
    chk("fwdCnt 非零字数", nz128(cap_fwdCnt), 4'd2);   // L3 + DROP

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
