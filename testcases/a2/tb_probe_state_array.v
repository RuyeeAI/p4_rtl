module tb_probe1;
  reg clk = 0; reg rst = 1;
  always #5 clk = ~clk;
  reg data_out_rdy = 1;
  wire [15:0] data_out;
  wire data_out_vld;
  extern_probe dut(.clk(clk), .rst(rst), .data_out_rdy(data_out_rdy), .data_out(data_out), .data_out_vld(data_out_vld));
  integer i;
  initial begin
    repeat (3) @(posedge clk);
    @(negedge clk); rst = 0;
    for (i = 0; i < 6; i = i + 1) begin
      @(negedge clk);
      $display("cycle %0d: data_out=%0d (vld=%b)", i, data_out, data_out_vld);
    end
    $finish;
  end
endmodule
