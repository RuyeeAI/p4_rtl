`timescale 1ns/1ps
// demo9-l3forwarder 的功能验证：A2 的**全特性综合样本**
//
// ============================ 被验对象 ============================
// testcases/p4/demo9-l3forwarder.p4 经 XlsBackend 生成的 out/a2/demo9-l3forwarder.v
//
//   parser 5 状态 2 select（顶层 const 引用：ET_IPV4/PROTO_UDP/PROTO_TCP）
//   control：classify（! / 切片 / 三元 / 一元负号 / ++ 字节交换）
//            forward（TTL 递减 + Counter.count + Register 读改写）
//            静态表 l2_fwd（dst MAC）+ runtime 表 acl（dst IP，trap）
//            Register portBytes(16x8) + Counter classPkts(32x4)
//
// ============================ 报文与 PHV（564 位） ============================
// 报文窗口 512 位：ethernet(112) + ipv4(160) + udp(64) 或 tcp(144) + padding
// PHV 按 header 声明序拼 (valid, 字段…) 再拼 meta（580 位：tcp 实际 160 位）：
//   [579] eth_v [562:515] dst [514:467] src [466:451] etype
//   [466] ipv4_v [465:306] ipv4 字段（ver/ihl/diffserv/totalLen/ident/flags/frag/
//         ttl/proto/cksum/src/dst 各归其位）
//   [305] udp_v [304:241] udp | [240] tcp_v [239:80] tcp
//   [79:64] normPort [63:56] cls [55:48] dropFlag [47:40] badVer
//   [39:32] ecnQ [31:16] flowHash [15:0] swapId
//
// ============================ 验收判据 ============================
// 包 1（UDP，dst MAC=DST1 → forward(1,1)，IP dst=0a000001 → acl miss）：
//   分类 badVer=0 ecnQ=0 flowHash=-(0^0+1234)=EDCC swapId=3412（字节交换）
//   转发 np=0001 cls=01 ttl=3F（0x40-1）；acl miss → dropFlag=0
//   extern：classPkts[1]=1、portBytes[1]=totalLen(0x43)
// 包 2（TCP，dst MAC=DST2 → forward(2,2)，IP dst=0a000002 → acl 命中 trap）：
//   dropFlag=1（trap）+ np=0002 cls=02 ttl=3F；flowHash=A988 swapId=7856
//   extern：classPkts[2]=1、portBytes[2]=0x36 —— **两个包互不串**
//
// 用法（一键：scripts/a2_verify.sh testcases/p4/demo9-l3forwarder.p4）

module tb_demo9_l3forwarder;

  reg clk = 0;
  reg rst = 1;
  always #5 clk = ~clk;

  reg  [511:0] pkt_in      = 0;
  reg          pkt_in_vld  = 0;
  reg          phv_out_rdy = 1;
  wire [579:0] phv_out;
  wire         phv_out_vld;
  wire [127:0] ex_portBytes;
  wire         ex_portBytes_vld;
  wire [127:0] ex_classPkts;
  wire         ex_classPkts_vld;
  wire [31:0]  tbl_acl_key;
  wire         tbl_acl_key_vld;
  reg  [1:0]   tbl_acl_rsp;
  reg          tbl_acl_rsp_vld;

  Ingress_pipeline dut (
      .clk(clk), .rst(rst),
      .pkt_in(pkt_in), .pkt_in_vld(pkt_in_vld), .phv_out_rdy(phv_out_rdy),
      .phv_out(phv_out), .phv_out_vld(phv_out_vld),
      .ex_portBytes(ex_portBytes), .ex_portBytes_vld(ex_portBytes_vld),
      .ex_classPkts(ex_classPkts), .ex_classPkts_vld(ex_classPkts_vld),
      .tbl_acl_key(tbl_acl_key), .tbl_acl_key_vld(tbl_acl_key_vld),
      .tbl_acl_rsp(tbl_acl_rsp), .tbl_acl_rsp_vld(tbl_acl_rsp_vld)
  );

  // mock acl 表（打一拍）：dst IP=0x0a000002 → trap(act=0)；否则 miss
  always @(posedge clk) begin
    if (tbl_acl_key_vld) begin
      tbl_acl_rsp <= (tbl_acl_key == 32'h0a00_0002) ? 2'b10 : 2'b00;
      tbl_acl_rsp_vld <= 1'b1;
    end else begin
      tbl_acl_rsp_vld <= 1'b0;
    end
  end

  integer errors = 0;

  localparam [47:0] DST1 = 48'h0011_2233_4455;   // l2_fwd 条目 0：forward(1,1)
  localparam [47:0] DST2 = 48'h00aabbccddee;     // l2_fwd 条目 1：forward(2,2)
  localparam [47:0] SRC  = 48'h6677_8899_aabb;

  localparam [159:0] IPV4_1 =
      {4'h4, 4'h5, 8'h00, 16'h0043, 16'h1234, 3'h0, 13'h0, 8'h40, 8'h11,
       16'hbeef, 32'h0a00_0001, 32'h0a00_0001};
  localparam [63:0] UDP_H = {16'h0035, 16'h0035, 16'h002b, 16'h0000};
  localparam [159:0] IPV4_2 =
      {4'h4, 4'h5, 8'h00, 16'h0036, 16'h5678, 3'h0, 13'h0, 8'h40, 8'h06,
       16'hbeef, 32'h0b00_0002, 32'h0a00_0002};
  localparam [143:0] TCP_H =
      {16'h1f90, 16'h0050, 32'h1000_0000, 32'h2000_0000, 4'h5, 3'h0, 3'h0,
       6'h02, 16'h7fff, 16'h0000, 16'h0000};

  task send_udp;
    begin
      pkt_in = {DST1, SRC, 16'h0800, IPV4_1, UDP_H, 176'h0};
      pkt_in_vld = 1;
    end
  endtask

  task send_tcp;
    begin
      pkt_in = {DST2, SRC, 16'h0800, IPV4_2, TCP_H, 96'h0};
      pkt_in_vld = 1;
    end
  endtask

  // 检查关键字段（不逐位比 564 位，只比“会被本程序改动”的字段 + 两个 valid）
  task check(input [15:0] np, input [7:0] cls, input [7:0] drop, input [7:0] badver,
             input [7:0] ecnq, input [15:0] flowhash, input [15:0] swapid,
             input [7:0] ttl, input udp_v, input tcp_v,
             input [15:0] portBytes1, input [15:0] portBytes2,
             input [31:0] classPkts1, input [31:0] classPkts2,
             input integer case_id);
    integer i;
    begin
      i = 0;
      while (phv_out_vld && i < 40) begin @(negedge clk); i = i + 1; end
      for (i = 0; i < 40 && !phv_out_vld; i = i + 1) @(negedge clk);
      if (!phv_out_vld) begin
        $display("  [FAIL] case %0d: no phv_out_vld", case_id);
        errors = errors + 1;
      end else begin
        if (phv_out[79:0]      !== {np, cls, drop, badver, ecnq, flowhash, swapid} ||
            phv_out[401:394]   !== ttl ||
            phv_out[305]       !== udp_v || phv_out[240] !== tcp_v ||
            phv_out[579]       !== 1'b1 || phv_out[466] !== 1'b1) begin
          $display("  [FAIL] case %0d: PHV mismatch", case_id);
          $display("        meta got = np=%h cls=%h drop=%h bad=%h ecn=%h hash=%h swap=%h",
                   phv_out[79:64], phv_out[63:56], phv_out[55:48], phv_out[47:40],
                   phv_out[39:32], phv_out[31:16], phv_out[15:0]);
          $display("        ttl=%h udp_v=%b tcp_v=%b",
                   phv_out[401:394], phv_out[305], phv_out[240]);
          errors = errors + 1;
        end else if (ex_portBytes[111:96] !== portBytes1 || ex_portBytes[95:80] !== portBytes2 ||
                     ex_classPkts[95:64] !== classPkts1 || ex_classPkts[63:32] !== classPkts2) begin
          $display("  [FAIL] case %0d: extern mismatch (pb[1]=%0d pb[2]=%0d cp[1]=%0d cp[2]=%0d)",
                   case_id, ex_portBytes[111:96], ex_portBytes[95:80],
                   ex_classPkts[95:64], ex_classPkts[63:32]);
          errors = errors + 1;
        end else begin
          $display("  [ok] case %0d: np=%h cls=%h drop=%h hash=%h swap=%h ttl=%h  pb[1]=%0d cp[1]=%0d",
                   case_id, phv_out[79:64], phv_out[63:56], phv_out[55:48],
                   phv_out[31:16], phv_out[15:0], phv_out[401:394],
                   ex_portBytes[111:96], ex_portBytes[95:80],
                   ex_classPkts[95:64], ex_classPkts[63:32]);
        end
      end
    end
  endtask

  integer i;
  initial begin
    $dumpfile("out/a2/tb_demo9.vcd");
    $dumpvars(0, tb_demo9_l3forwarder);

    $display("============================================================");
    $display(" demo9-l3forwarder：parser + control + 双表 + Register/Counter");
    $display("============================================================");

    rst = 1; pkt_in_vld = 0;
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;

    // 包 1：UDP，l2_fwd 命中 forward(1,1)，acl miss
    @(negedge clk); send_udp;
    check(16'h0001, 8'h01, 8'h00, 8'h00, 8'h00, 16'hEDCC, 16'h3412,
          8'h3F, 1'b1, 1'b0, 16'd67, 16'd0, 32'd1, 32'd0, 1);
    send_tcp;
    check(16'h0002, 8'h02, 8'h01, 8'h00, 8'h00, 16'hA988, 16'h7856,
          8'h3F, 1'b0, 1'b1, 16'd67, 16'd54, 32'd1, 32'd1, 2);
    pkt_in_vld = 0;

    $display("============================================================");
    if (errors == 0) $display(" 结果：全部通过");
    else             $display(" 结果：%0d 处失败", errors);
    $display("============================================================");
    $finish;
  end

  initial begin
    #40000;
    $display("[FAIL] 仿真超时");
    $finish;
  end

endmodule
