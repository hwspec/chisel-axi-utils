module Dut #(
    parameter integer BW = 32
) (
    input  wire          clock,
    input  wire          reset,
    input  wire [BW-1:0] in,
    input  wire          valid,
    output wire [BW-1:0] out
);

    reg [BW-1:0] valReg;

    always @(posedge clock) begin
        if (reset) begin
            valReg <= {BW{1'b0}};
        end else if (valid) begin
            valReg <= in;
        end
    end

    assign out = valReg;

endmodule // Dut



