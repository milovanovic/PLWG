package plfg

import chisel3._
import chisel3.util._
import chisel3.experimental._
import dsptools._
import dsptools.numbers._

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


class PLFGDspBlockMemTester
(
  dut: PLFGDspBlockMem[FixedPoint] with AXI4Block,
  csrAddress: AddressSet,
  ramAddress1: AddressSet,
  beatBytes : Int,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel {
  override def memAXI: AXI4Bundle = dut.ioMem.get.getWrappedValue
  
  val mod = dut.module
  val params = dut.params
  val slave = bindSlave(dut.out.getWrappedValue)
  
  
  val widthOfFields = params.outputWidthInt + params.maxNumOfSamplesWidth + 2 // 16 + 8 + 1 + 1
  // segmentResets(1)_slopeSigns(1)_numsOfSamples(8)_slopes(16)
  memWriteWord(ramAddress1.base, 0x1100003)
  step(1)
  memWriteWord(ramAddress1.base + beatBytes, 0x20A0003)
  step(1)
  memWriteWord(ramAddress1.base + params.maxNumOfSegments * beatBytes, 0x10C0004)
  step(1)
  memWriteWord(ramAddress1.base + (params.maxNumOfSegments + 1) * beatBytes, 0x2060004)
  step(1)
  memWriteWord(ramAddress1.base + 2 * params.maxNumOfSegments * beatBytes, 0x1180002)
  step(1)
  memWriteWord(ramAddress1.base + (2 * params.maxNumOfSegments + 1) * beatBytes, 0x2060002) // 0x2060002
  step(1)
  memWriteWord(ramAddress1.base + 3 * params.maxNumOfSegments * beatBytes, 0x1080006)
  step(1)
  memWriteWord(ramAddress1.base + (3 * params.maxNumOfSegments + 1) * beatBytes, 0x2040006)//0x2040006
  step(1)
  
  step(5)
  
  
  val expectedDepth = 500
  slave.addExpects((0 until expectedDepth*2).map(x => AXI4StreamTransactionExpect(data = Some(x))))
  step(1)
  
  val segmentNumsArrayOffset = 6 * beatBytes
  val repeatedChirpNumsArrayOffset = segmentNumsArrayOffset + params.maxChirpOrdinalNum * beatBytes
  val chirpOrdinalNumsArrayOffset = repeatedChirpNumsArrayOffset + params.maxNumOfDifferentChirps * beatBytes
  
  memWriteWord(csrAddress.base + 2*beatBytes, 2) // frameNum
  step(1)
  memWriteWord(csrAddress.base + 3*beatBytes, 60) // interframeNumOfSamples
  step(1)
  memWriteWord(csrAddress.base + 4*beatBytes, 5) // differentChirpsNum
  step(1)
  memWriteWord(csrAddress.base + 5*beatBytes, 0) // startingPoint
  step(1)
  
  memWriteWord(csrAddress.base + segmentNumsArrayOffset, 2) // segmentNums
  step(1)
  memWriteWord(csrAddress.base + segmentNumsArrayOffset + beatBytes, 2) // segmentNums
  step(1)
  memWriteWord(csrAddress.base + segmentNumsArrayOffset + 2*beatBytes, 2) // segmentNums
  step(1)
  memWriteWord(csrAddress.base + segmentNumsArrayOffset + 3*beatBytes, 2) // segmentNums
  step(1)
  
  
  // repeatedChirpNums + chirpOrdinalNums
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset, 3)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset, 2)
  step(1)
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset + beatBytes, 4)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset + beatBytes, 0) //0
  step(1)
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset + 2*beatBytes, 1)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset + 2*beatBytes, 2) //2
  step(1)
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset + 3*beatBytes, 5)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset + 3*beatBytes, 1) //1
  step(1)
  memWriteWord(csrAddress.base + repeatedChirpNumsArrayOffset + 4*beatBytes, 2)
  step(1)
  memWriteWord(csrAddress.base + chirpOrdinalNumsArrayOffset + 4*beatBytes, 3) //3
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
  
  
  stepToCompletion(expectedDepth*3, silentFail = silentFail)
  
}


class PLFGDspBlockMemSpec extends FlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty
  
  val paramsPLFG = FixedPLFGParams(
    maxNumOfSegments = 4,
    maxNumOfDifferentChirps = 8,
    maxNumOfRepeatedChirps = 8,
    maxChirpOrdinalNum = 4,
    maxNumOfFrames = 4,
    maxNumOfSamplesWidth = 8,
    outputWidthInt = 16,
    outputWidthFrac = 0
  )
  
  it should "Test PLFG DspBlock with SyncReadMem" in {
    val lazyDut = LazyModule(new PLFGDspBlockMem(csrAddress = AddressSet(0x010000, 0xFF), ramAddress1 = AddressSet(0x000000, 0x0FFF), paramsPLFG, beatBytes = 4) with AXI4Block {
      override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
    })
    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new PLFGDspBlockMemTester(lazyDut,  AddressSet(0x010000, 0xFF), AddressSet(0x000000, 0xFFF), 4, true)
    } should be (true)
  }
}




