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


case class PLFGParams[T <: Data]
(
  maxNumOfSegments: Int,
  maxNumOfDifferentChirps: Int,
  repeatedChirpNumWidth: Int,
  maxChirpOrdinalNum: Int,
  chirpOrdinalNumWidth: Int,
  maxNumOfFrames: Int,
  segmentNumWidth: Int,
  differentChirpNumWidth: Int,
  frameNumWidth: Int,
  maxNumOfSamplesWidth: Int,
  protoOut: T, //UInt,//T,
  protoNumOfSamples: UInt,
  outputWidthInt: Int,
  outputWidthFrac: Int
) 	{
  requireIsChiselType(protoOut,  s"($protoOut) must be chisel type")
}

object FixedPLFGParams {
  def apply(maxNumOfSegments: Int, maxNumOfDifferentChirps: Int, maxNumOfRepeatedChirps: Int, maxChirpOrdinalNum: Int, maxNumOfFrames: Int, maxNumOfSamplesWidth: Int, outputWidthInt: Int, outputWidthFrac: Int): PLFGParams[FixedPoint] = {
    require(outputWidthInt >= 2)
    require(outputWidthFrac >= 0)
    require(isPow2(maxNumOfSegments))
    require(isPow2(maxNumOfDifferentChirps))
    require(isPow2(maxNumOfRepeatedChirps))
    require(isPow2(maxChirpOrdinalNum))
    require(isPow2(maxNumOfFrames))
    val segmentNumWidth = log2Ceil(maxNumOfSegments).toInt
    val differentChirpNumWidth = log2Ceil(maxNumOfDifferentChirps).toInt
    val repeatedChirpNumWidth = log2Ceil(maxNumOfRepeatedChirps).toInt
    val chirpOrdinalNumWidth = log2Ceil(maxChirpOrdinalNum).toInt
    val frameNumWidth = log2Ceil(maxNumOfFrames).toInt
    PLFGParams(
      maxNumOfSegments = maxNumOfSegments,
      maxNumOfDifferentChirps = maxNumOfDifferentChirps,
      repeatedChirpNumWidth = repeatedChirpNumWidth,
      maxChirpOrdinalNum = maxChirpOrdinalNum,
      chirpOrdinalNumWidth = chirpOrdinalNumWidth,
      maxNumOfFrames = maxNumOfFrames,
      segmentNumWidth = segmentNumWidth,
      differentChirpNumWidth = differentChirpNumWidth,
      frameNumWidth = frameNumWidth,
      protoOut = FixedPoint((outputWidthInt+outputWidthFrac).W, (outputWidthFrac).BP),
      //protoOut = UInt(outputWidthInt.W),
      maxNumOfSamplesWidth = maxNumOfSamplesWidth,
      protoNumOfSamples = UInt(maxNumOfSamplesWidth.W),
      outputWidthInt = outputWidthInt,
      outputWidthFrac = outputWidthFrac
    )
  }
}

class PLFGDspBlockMem [T <: Data : Real: BinaryRepresentation] (csrAddress: AddressSet, ramAddress1: AddressSet, val params: PLFGParams[T], beatBytes: Int) extends LazyModule()(Parameters.empty) with AXI4DspBlock {

  val zero = ConvertableTo[T].fromDouble(0.0)
  
  val streamNode = AXI4StreamMasterNode(Seq(AXI4StreamMasterPortParameters(
    Seq(AXI4StreamMasterParameters(
      "plfgOut",
      n = beatBytes
  )))))
  
  
  val mem = Some(AXI4IdentityNode())
  val axiRegSlaveNode = AXI4RegisterNode(address = csrAddress, beatBytes = beatBytes) // AXI4 Register
  
  val ramSlaveNode1 = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
    Seq(AXI4SlaveParameters(
      address       = Seq(ramAddress1),
      supportsRead  = TransferSizes(1, beatBytes),
      supportsWrite = TransferSizes(1, beatBytes),
      interleavedId = Some(0))),
      beatBytes  = beatBytes,
      minLatency = 1)))
    
    
  val topXbar = AXI4Xbar()
  
  ramSlaveNode1   := topXbar
  axiRegSlaveNode := topXbar
  topXbar         := mem.get

  lazy val module = new LazyModuleImp(this) {
    val (out, _) = streamNode.out(0)
      
    val (ramIn1, ramInEdge1) = ramSlaveNode1.in.head
    
    val numberOfElements = params.maxChirpOrdinalNum * params.maxNumOfSegments
    val widthOfFields = params.outputWidthInt + params.maxNumOfSamplesWidth + 2
    
    val configParamsMem1 = SyncReadMem(numberOfElements, UInt(widthOfFields.W))
    
    val r_addr = Wire(UInt(log2Ceil(numberOfElements).W))
    
    val w_full1 = RegInit(false.B)
    val wdata1 = ramIn1.w.bits.data.asTypeOf(UInt(widthOfFields.W))
        
    val w_addr1 = (ramIn1.aw.bits.addr(log2Ceil(numberOfElements) - 1 + log2Ceil(beatBytes), 0) >> log2Ceil(beatBytes)).asTypeOf(r_addr)
    
    ramIn1.aw.ready := ramIn1. w.valid && (ramIn1.b.ready || !w_full1)
    ramIn1.w.ready := ramIn1.aw.valid && (ramIn1.b.ready || !w_full1)
    when (ramIn1. b.fire()) { w_full1 := false.B }
    when (ramIn1.aw.fire()) { w_full1 := true.B }
    ramIn1. b.valid := w_full1
    when (ramIn1.aw.fire()) {
      configParamsMem1.write(w_addr1, wdata1)
    }
    
    
    //control registers
    val enable = RegInit(false.B)
    val reset_bit = RegInit(false.B)
    val frameNum = RegInit(UInt(params.frameNumWidth.W), 1.U)
    val startingPoint = RegInit(UInt(params.outputWidthInt.W), 0.U)
    val interframeNumOfSamples = RegInit(params.protoNumOfSamples, ConvertableTo[T].fromDouble(63.0).asTypeOf(params.protoNumOfSamples))
    val differentChirpsNum = RegInit(UInt(params.differentChirpNumWidth.W), 1.U)
    
    
    val currentVal = RegInit(params.protoOut, startingPoint.asTypeOf(params.protoOut))
    val samplesCounter = RegInit(params.protoNumOfSamples, 0.U)
    val segmentCounter = RegInit(UInt(params.segmentNumWidth.W), 0.U)
    val frameCounter = RegInit(UInt(params.frameNumWidth.W), 0.U)
    val framePauseCounter = RegInit(UInt(6.W), 0.U)
    val end = RegInit(Bool(), false.B)
    
    val samplesCounter2 = RegNext(RegNext(samplesCounter, 0.U), 0.U)
    val segmentCounter2 = RegNext(RegNext(segmentCounter, 0.U), 0.U)
    val frameCounter2 = RegNext(RegNext(frameCounter, 0.U), 0.U)
    val framePauseCounter2 = RegNext(RegNext(framePauseCounter, 0.U), 0.U)
    //val end2 = RegInit(Bool(), false.B)
    val end2 = RegNext(RegNext(end, false.B), false.B)

    val repeatedChirpsCounter = RegInit(UInt(params.repeatedChirpNumWidth.W), 0.U)
    val differentChirpsCounter = RegInit(UInt(params.differentChirpNumWidth.W), 0.U)

    val repeatedChirpsCounter2 = RegNext(RegNext(repeatedChirpsCounter))
    val differentChirpsCounter2 = RegNext(RegNext(differentChirpsCounter))


    val segmentNums = RegInit(VecInit(Seq.fill(params.maxChirpOrdinalNum)(ConvertableTo[T].fromDouble(1.0).asTypeOf(UInt(params.segmentNumWidth.W)))))
    val repeatedChirpNums = RegInit(VecInit(Seq.fill(params.maxNumOfDifferentChirps)(zero.asTypeOf(UInt(params.repeatedChirpNumWidth.W)))))
    val chirpOrdinalNums = RegInit(VecInit(Seq.fill(params.maxNumOfDifferentChirps)(zero.asTypeOf(UInt(params.chirpOrdinalNumWidth.W)))))
    
    val dataFromMem = Wire(UInt(widthOfFields.W))
    dataFromMem := (configParamsMem1(r_addr))
    
    val point1 = params.outputWidthInt + params.maxNumOfSamplesWidth + 2
    val point2 = params.outputWidthInt + params.maxNumOfSamplesWidth + 1
    val point3 = params.outputWidthInt + params.maxNumOfSamplesWidth
    val point4 = params.outputWidthInt
    val point5 = 0
    
    val slopes = dataFromMem(point4 - 1, point5)
    val numsOfSamples = Mux(dataFromMem(point3 - 1, point4) > 1, dataFromMem(point3 - 1, point4), dataFromMem(point3 - 1, point4) + 1)
    //val numsOfSamples = dataFromMem(point3 - 1, point4)
    val slopeSigns = dataFromMem(point2 - 1)
    val segmentResets = dataFromMem(point1 - 1)
    
    //require(numsOfSamples > 1)
    
    var fields = Seq(
      RegField(1, enable,
        RegFieldDesc(name = "enable", desc = "enable bit")),
      RegField(1, reset_bit,
        RegFieldDesc(name = "reset", desc = "reset bit")),
      RegField(params.frameNumWidth, frameNum,
        RegFieldDesc(name = "frameNum", desc = "number of frames")),
      RegField(10, interframeNumOfSamples,
        RegFieldDesc(name = "interframeNumOfSamples", desc = "number of zero samples between frames")),
      RegField(params.differentChirpNumWidth, differentChirpsNum,
        RegFieldDesc(name = "differentChirpsNum", desc = "number of chirps on the output")),
      RegField(params.outputWidthInt, startingPoint,
        RegFieldDesc(name = "startingPoint", desc = "starting point")),
    )

    
    for (i <- 0 to params.maxChirpOrdinalNum-1) {
      fields = fields++Seq(
        RegField(params.segmentNumWidth, segmentNums(i),
          RegFieldDesc(name = "segmentNums($i)", desc = "segmentNums($i)")),
      )
    }
    for (i <- 0 to params.maxNumOfDifferentChirps-1) {
      fields = fields++Seq(
        RegField(params.repeatedChirpNumWidth, repeatedChirpNums(i),
          RegFieldDesc(name = "repeatedChirpNums($i)", desc = "repeatedChirpNums($i)")),
      )
    }
    for (i <- 0 to params.maxNumOfDifferentChirps-1) {
      fields = fields++Seq(
        RegField(params.chirpOrdinalNumWidth, chirpOrdinalNums(i),
          RegFieldDesc(name = "chirpOrdinalNums($i)", desc = "chirpOrdinalNums($i)")),
      )
    }
    
    axiRegSlaveNode.regmap(fields.zipWithIndex.map({ case (f, i) => i * beatBytes -> Seq(f)}): _*)
    
    
    val chirpIndex = Wire(UInt(params.chirpOrdinalNumWidth.W))
    val enable2 = RegNext(RegNext(enable, false.B), false.B)
    val chirpIndexOld = RegNext(chirpIndex)
    val started = RegInit(false.B)
    val outReady = RegInit(Bool(), false.B)
    outReady := out.ready
    
    when(reset_bit){
      samplesCounter := 0.U
      segmentCounter := 0.U
      repeatedChirpsCounter := 0.U
      differentChirpsCounter := 0.U
      frameCounter := 0.U
      framePauseCounter := 0.U
      out.bits.data := startingPoint
      out.bits.last := end
      chirpIndex := 0.U

      repeatedChirpsCounter := 0.U
      differentChirpsCounter := 0.U
    }.otherwise{
      chirpIndex := chirpOrdinalNums(differentChirpsCounter)
      when(enable && out.ready && !end){
        chirpIndex := chirpOrdinalNums(differentChirpsCounter)
        when((samplesCounter + 1.U) >= numsOfSamples){
          when((segmentCounter + 1.U) >= segmentNums(chirpIndex)){
            when((repeatedChirpsCounter + 1.U) >= repeatedChirpNums(differentChirpsCounter)){
              when((differentChirpsCounter + 1.U) >= differentChirpsNum){
                when(frameNum === zero.asTypeOf(UInt(params.frameNumWidth.W))){
                  end := false.B
                  when((framePauseCounter + 1.U) >= interframeNumOfSamples){
                    samplesCounter := 0.U
                    segmentCounter := 0.U
                    repeatedChirpsCounter := 0.U
                    differentChirpsCounter := 0.U
                    frameCounter := 0.U
                    framePauseCounter := 0.U
                  }.otherwise{
                    framePauseCounter := framePauseCounter + 1.U
                  }
                }.otherwise{
                  when((frameCounter + 1.U) >= frameNum){
                    end := true.B
                  }.otherwise{
                    when((framePauseCounter + 1.U) >= interframeNumOfSamples){
                      samplesCounter := 0.U
                      segmentCounter := 0.U
                      repeatedChirpsCounter := 0.U
                      differentChirpsCounter := 0.U
                      frameCounter := frameCounter + 1.U
                      framePauseCounter := 0.U
                    }.otherwise{
                      framePauseCounter := framePauseCounter + 1.U
                    }
                  }
                }
              }.otherwise{
                samplesCounter := 0.U
                segmentCounter := 0.U
                repeatedChirpsCounter := 0.U
                differentChirpsCounter := differentChirpsCounter + 1.U
              }
            }.otherwise{
              samplesCounter := 0.U
              segmentCounter := 0.U
              repeatedChirpsCounter := repeatedChirpsCounter + 1.U
            }
          }.otherwise{
            samplesCounter := 0.U
            segmentCounter := segmentCounter + 1.U
          }
        }.otherwise{
          samplesCounter := samplesCounter + 1
        }
      }
    }
    
    
    when(reset_bit){
      currentVal := startingPoint.asTypeOf(params.protoOut)
    }.otherwise{
      when(enable2 && out.ready && !end2){
        started := true.B
        when((samplesCounter2 + 1.U) >= RegNext(numsOfSamples, 0.U)){
          when(((segmentCounter2 + 1.U) >= segmentNums(chirpIndexOld))) {
            currentVal := startingPoint.asTypeOf(params.protoOut)
            when((repeatedChirpsCounter2 + 1.U) >= repeatedChirpNums(differentChirpsCounter)){
              when((differentChirpsCounter2 + 1.U) >= differentChirpsNum){
                when(frameNum === zero.asTypeOf(UInt(params.frameNumWidth.W))){
                  //end2 := false.B
                  currentVal := startingPoint.asTypeOf(params.protoOut)
                }.otherwise{
                  when(frameCounter2 >= frameNum){
                    //end2 := true.B
                    currentVal := startingPoint.asTypeOf(params.protoOut)
                  }.otherwise{
                    currentVal := startingPoint.asTypeOf(params.protoOut)
                  }
                }
              }.otherwise{
                currentVal := startingPoint.asTypeOf(params.protoOut)
              }
            }.otherwise{
                currentVal := startingPoint.asTypeOf(params.protoOut)
            }
          }.otherwise{
            when(segmentResets){
              currentVal := startingPoint.asTypeOf(params.protoOut)
            }.otherwise{
              currentVal := Mux(slopeSigns, currentVal + slopes.asTypeOf(params.protoOut), currentVal - slopes.asTypeOf(params.protoOut))
            }
          }
        }.otherwise{
          when(segmentResets){
            currentVal := startingPoint.asTypeOf(params.protoOut)
          }.otherwise{
            currentVal := Mux(slopeSigns, currentVal + slopes.asTypeOf(params.protoOut), currentVal - slopes.asTypeOf(params.protoOut))
          }
        }
      }.otherwise{
        when(!started) {
          currentVal := startingPoint.asTypeOf(params.protoOut)
        }
      }
      out.bits.data := currentVal.asUInt
    }
    
    when (out.ready) {
      out.valid := enable2 && !end2//!RegNext(end)
      //out.valid := RegNext(enable2) && !RegNext(end)
    }.otherwise {
      out.valid := out.ready
    }
    val lastOut = Wire(Bool())
    val lastOutReg = RegInit(Bool(), false.B)
    
    when(RegNext(end) && !end2){
      lastOut := true.B
    }.otherwise{
      lastOut := false.B
    }
    when(RegNext(end) && !end2 && !out.ready){
      lastOutReg := true.B
    }.elsewhen(out.ready && end2){
      lastOutReg := false.B
    }
    out.bits.last := Mux(out.ready, (lastOut || lastOutReg), false.B)
    
    /*when(end && !RegNext(end)){
      lastOut := true.B
    }.otherwise{
      lastOut := false.B
    }
    when(end && !RegNext(end)){
      lastOutReg := true.B
    }.elsewhen(out.ready && RegNext(end)){
      lastOutReg := RegNext(false.B)
    }
    out.bits.last := Mux(out.ready, (lastOut || lastOutReg), false.B)*/

    
    when(!reset_bit) {
      when(enable) {
        r_addr := (chirpIndex * params.maxNumOfSegments + segmentCounter).asTypeOf(r_addr)
      
      }.otherwise {
        r_addr := 0.U
      }
    }.otherwise {
      r_addr := 0.U
    }
  }
}    


trait AXI4Block extends DspBlock[
  AXI4MasterPortParameters,
  AXI4SlavePortParameters,
  AXI4EdgeParameters,
  AXI4EdgeParameters,
  AXI4Bundle] {
    def standaloneParams = AXI4BundleParameters(addrBits = 64, dataBits = 64, idBits = 1)
    val ioMem = mem.map { m => {
      val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

      m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

      val ioMem = InModuleBody { ioMemNode.makeIO() }
      ioMem
    }}

    val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

    ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    streamNode

    val out = InModuleBody { ioOutNode.makeIO() }
}


object PLFGDspBlockMemApp extends App
{

  trait AXI4Block extends DspBlock[
  AXI4MasterPortParameters,
  AXI4SlavePortParameters,
  AXI4EdgeParameters,
  AXI4EdgeParameters,
  AXI4Bundle] {
    def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
    val ioMem = mem.map { m => {
      val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

      m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

      val ioMem = InModuleBody { ioMemNode.makeIO() }
      ioMem
    }}

    val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

    ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    streamNode

    val out = InModuleBody { ioOutNode.makeIO() }

  }
    
    
    
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
  
  implicit val p: Parameters = Parameters.empty
  val testModule = LazyModule(new PLFGDspBlockMem(csrAddress = AddressSet(0x010000, 0xFF), ramAddress1 = AddressSet(0x000000, 0x0FFF), paramsPLFG, beatBytes = 4) with AXI4Block {
    override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  })
  chisel3.Driver.execute(args, ()=> testModule.module)
}


