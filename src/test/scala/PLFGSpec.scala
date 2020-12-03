package plfg

import breeze.math.Complex
import chisel3.util.{log2Ceil, log2Floor}
import chisel3._
import chisel3.experimental.FixedPoint
import dsptools._
import dsptools.DspTester
import scala.io.Source

import dspblocks._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.tilelink._
import chisel3.iotesters.Driver
import chisel3.iotesters.PeekPokeTester
import org.scalatest.{FlatSpec, Matchers}


class PLFGTester
(
  dut: PLFGDspBlockMem[FixedPoint] with AXI4Block,
  csrAddress: AddressSet,
  ramAddress1: AddressSet,
  beatBytes : Int,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel with HasPLFGTesterUtil {
  override def memAXI: AXI4Bundle = dut.ioMem.get.getWrappedValue
  
  val mod = dut.module
  val params = dut.params
  val slave = bindSlave(dut.out.getWrappedValue)
  
  
  val widthOfFields = params.outputWidthInt + params.maxNumOfSamplesWidth + 2 // 16 + 8 + 1 + 1
  // segmentResets(1)_slopeSigns(1)_numsOfSamples(8)_slopes(16)
  memWriteWord(ramAddress1.base, 0x1180002)
  step(1)
  memWriteWord(ramAddress1.base + beatBytes, 0x2060000)
  step(1)
  memWriteWord(ramAddress1.base + 2*beatBytes, 0x120000A)
  step(1)
  memWriteWord(ramAddress1.base + 3*beatBytes, 0x0140008)
  step(1)

  
  step(5)
  
  val expectedVals1 = rampPLFG(0, 25, 2)
  val expectedVals2 = constantPLFG(0, 6)
  val expectedVals3 = trianglePLFG(10, 32, 20, 10, 8)
  val expectedDepth1 = 25
  val expectedDepth2 = 6
  val expectedDepth3 = 52
  slave.addExpects((0 until expectedDepth1).map(x => AXI4StreamTransactionExpect(data = Some(expectedVals1(x)))))
  slave.addExpects((0 until expectedDepth2).map(x => AXI4StreamTransactionExpect(data = Some(expectedVals2(x)))))
  slave.addExpects((0 until expectedDepth3).map(x => AXI4StreamTransactionExpect(data = Some(expectedVals3(x)))))
  step(1)
  
  val segmentNumsArrayOffset = 6 * beatBytes
  val repeatedChirpNumsArrayOffset = segmentNumsArrayOffset + params.maxChirpOrdinalNum * beatBytes
  val chirpOrdinalNumsArrayOffset = repeatedChirpNumsArrayOffset + params.maxNumOfDifferentChirps * beatBytes
  
  memWriteWord(csrAddress.base + 2*beatBytes, 1) // frameNum
  step(1)
  memWriteWord(csrAddress.base + 3*beatBytes, 60) // interframeNumOfSamples
  step(1)
  memWriteWord(csrAddress.base + 4*beatBytes, 1) // differentChirpsNum
  step(1)
  memWriteWord(csrAddress.base + 5*beatBytes, 0) // startingPoint
  step(1)
  
  memWriteWord(csrAddress.base + segmentNumsArrayOffset, 4) // segmentNums
  step(1)
  
  
  // repeatedChirpNums + chirpOrdinalNums
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset, 1)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset, 0)
  step(1)
  
  memWriteWord(csrAddress.base + beatBytes, 0) // reset_bit
  step(1)
  memWriteWord(csrAddress.base, 1) // enable
  step(1)
  /*poke(dut.out.ready, 1)
  step(100)
  poke(dut.out.ready, 0)
  step(20)
  poke(dut.out.ready, 1)
  step(1)*/
  
  
  stepToCompletion(150, silentFail = silentFail)
  
}


class PLFGSpec extends FlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty
  
  val paramsPLFG = FixedPLFGParams(
    maxNumOfSegments = 8,
    maxNumOfDifferentChirps = 8,
    maxNumOfRepeatedChirps = 8,
    maxChirpOrdinalNum = 4,
    maxNumOfFrames = 4,
    maxNumOfSamplesWidth = 8,
    outputWidthInt = 16,
    outputWidthFrac = 0
  )
  
  it should "Test PLFG" in {
    val lazyDut = LazyModule(new PLFGDspBlockMem(csrAddress = AddressSet(0x010000, 0xFF), ramAddress1 = AddressSet(0x000000, 0x0FFF), paramsPLFG, beatBytes = 4) with AXI4Block {
      override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
    })
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new PLFGTester(lazyDut,  AddressSet(0x010000, 0xFF), AddressSet(0x000000, 0xFFF), 4, true)
    } should be (true)
  }
}



