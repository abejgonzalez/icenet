package icenet

import chisel3._
import chisel3.util._
import freechips.rocketchip.unittest.UnitTest
import scala.util.Random
import testchipip.{StreamIO, StreamChannel}
import IceNetConsts._

/**
 * Buffer that holds items using the Mem construct
 *
 * @param n size of buffer
 * @param typ type of data to store
 */
class BufferBRAM[T <: Data](n: Int, typ: T) extends Module {
  val addrBits = log2Ceil(n)
  val io = IO(new Bundle {
    val read = new Bundle {
      val en = Input(Bool())
      val addr = Input(UInt(addrBits.W))
      val data = Output(typ)
    }
    val write = new Bundle {
      val en = Input(Bool())
      val addr = Input(UInt(addrBits.W))
      val data = Input(typ)
    }
  })

  val ram = Mem(n, typ)

  val wen = RegNext(io.write.en, false.B)
  val waddr = RegNext(io.write.addr)
  val wdata = RegNext(io.write.data)

  when (wen) { ram.write(waddr, wdata) }

  val rread_data = RegEnable(ram.read(io.read.addr), io.read.en)
  val rbypass = RegEnable(io.read.addr === waddr && wen, io.read.en)
  val rbypass_data = RegEnable(wdata, io.read.en)

  io.read.data := Mux(rbypass, rbypass_data, rread_data)
}

/**
 * Class representing data and keep values associated with it.
 *
 * @param w width of the data
 */
class DataKeepType(val w: Int) extends Bundle {
  val data = UInt(w.W)
  val keep = UInt((w/8).W)

  override def cloneType = new DataKeepType(w).asInstanceOf[this.type]
}

/**
 * Creates a network packet buffer that is used to store the packets gotten from the network.
 * This structure will drop a entire packet if there is not enough space to hold all of the packet.
 *
 * @param nPackets number of packets to send over the network
 * @param maxBytes max size in bytes of the packet
 * @param headerBytes size of the header in bytes
 * @param headerType class of header to be used
 * @param wordBytes size of a flit (split of the data to send)
 */
class NetworkPacketBuffer[T <: Data](
    nPackets: Int,
    bufBytesPerPacket: Int = ETH_MAX_BYTES,
    maxBytes: Int = ETH_MAX_BYTES,
    headerBytes: Int = ETH_HEAD_BYTES,
    headerType: T = new EthernetHeader,
    wordBytes: Int) extends Module {

  val bufWordsPerPacket = if(wordBytes > bufBytesPerPacket) 1 else bufBytesPerPacket/wordBytes
  val maxWords = if(wordBytes > maxBytes) 1 else maxBytes/wordBytes
  val headerWords = if(wordBytes > headerBytes) 1 else headerBytes/wordBytes
  val wordBits = wordBytes * 8
  val bufWords = bufWordsPerPacket * nPackets

  val idxBits = log2Ceil(bufWords)
  val phaseBits = log2Ceil(nPackets)

  val io = IO(new Bundle {
    val stream = new StreamIO(wordBytes * 8)
    val header = Valid(headerType)
    val length = Output(UInt(idxBits.W))
    val count = Output(UInt(log2Ceil(nPackets+1).W))
  })

  // TODO: AJG: Should this be andR or orR?. If valid = true then there should be a byte to keep...
  assert(!io.stream.in.valid || io.stream.in.bits.keep.orR,
    "NetworkPacketBuffer does not handle missing data")

  val buffer = Module(new BufferBRAM(bufWords, new DataKeepType(wordBits)))
  val headers = Reg(Vec(nPackets, Vec(headerWords, Bits(wordBits.W))))
  val bufLengths = RegInit(VecInit(Seq.fill(nPackets) { 0.U(idxBits.W) }))
  val bufValid = Vec(bufLengths.map(len => len > 0.U))

  val bufHead = RegInit(0.U(idxBits.W))
  val bufTail = RegInit(0.U(idxBits.W))
  val startHead = Reg(UInt(idxBits.W))
  val revertHead = WireInit(false.B)

  val maybeFull = RegInit(false.B)
  val ptrMatch = bufHead === bufTail
  val bufFull = maybeFull && ptrMatch
  val bufEmpty = !maybeFull && ptrMatch

  val inIdx = RegInit(0.U(idxBits.W))
  val inDrop = RegInit(false.B)
  val outIdx = RegInit(0.U(idxBits.W))

  val inPhase = RegInit(0.U(phaseBits.W))
  val outPhase = RegInit(0.U(phaseBits.W))

  val outLast = Vec(bufLengths.map(len => outIdx === (len - 1.U)))
  val outValidReg = RegInit(false.B)

  val ren = (io.stream.out.ready || !outValidReg) && bufValid(outPhase) && !bufEmpty
  val wen = Wire(init = false.B)
  val hwen = wen && inIdx < headerWords.U

  val outLastReg = RegEnable(outLast(outPhase), ren)
  val outIdxReg = RegEnable(outIdx, ren)

  io.stream.out.valid := outValidReg
  io.stream.out.bits.data := buffer.io.read.data.data
  io.stream.out.bits.last := outLastReg
  io.stream.out.bits.keep := buffer.io.read.data.keep
  io.stream.in.ready := true.B
  io.header.valid := bufValid(outPhase)
  io.header.bits := headerType.fromBits(Cat(headers(outPhase).reverse))
  io.length := RegEnable(bufLengths(outPhase), ren) - outIdxReg
  io.count := RegEnable(PopCount(bufValid), ren)

  def wrapInc(x: UInt, n: Int) = Mux(x === (n - 1).U, 0.U, x + 1.U)

  buffer.io.read.en := ren
  buffer.io.read.addr := bufTail
  buffer.io.write.en := wen
  buffer.io.write.addr := bufHead
  buffer.io.write.data := Cat(io.stream.in.bits.data, io.stream.in.bits.keep).asTypeOf(new DataKeepType(wordBits))

  val startDropping =
    (inPhase === outPhase && bufValid(inPhase)) ||
    (inIdx === maxWords.U) || bufFull

  when (io.stream.out.fire()) { outValidReg := false.B }

  when (ren) {
    outValidReg := true.B
    bufTail := wrapInc(bufTail, bufWords)
    outIdx := outIdx + 1.U

    when (outLast(outPhase)) {
      outIdx := 0.U
      outPhase := wrapInc(outPhase, nPackets)
      bufLengths(outPhase) := 0.U
    }
  }

  when (hwen) { headers(inPhase)(inIdx) := io.stream.in.bits.data }

  when (wen) { bufHead := wrapInc(bufHead, bufWords) }

  when (ren =/= wen) { maybeFull := wen }

  when (io.stream.in.fire()) {
    when (inIdx === 0.U) { startHead := bufHead }
    when (startDropping) { inDrop := true.B }
    wen := !startDropping && !inDrop
    when (inIdx =/= (bufWords - 1).U) { inIdx := inIdx + 1.U }

    when (io.stream.in.bits.last) {
      val nextPhase = wrapInc(inPhase, nPackets)
      val tooSmall = inIdx < (headerWords - 1).U
      when (!startDropping && !inDrop && !tooSmall) {
        inPhase := nextPhase
        bufLengths(inPhase) := inIdx + 1.U
      } .otherwise {
        wen := false.B
        revertHead := inIdx =/= 0.U
        printf("WARNING: dropped packet with %d flits\n", inIdx + 1.U)
      }
      inIdx := 0.U
      inDrop := false.B
    }
  }

  when (revertHead) {
    bufHead := startHead
    maybeFull := false.B
  }
}

/**
 * Unit test for the NetworkPacketBuffer class
 *
 * @param netIfWidthBits flit size of the network
 */
class NetworkPacketBufferTest(netIfWidthBits: Int = 64) extends UnitTest(100000) {
  val buffer = Module(new NetworkPacketBuffer(2, 24, 32, 8, UInt(64.W), 4))

  val inPackets = Seq(
    (10, false), // drop because too long
    (4,  true),
    (1,  false), // drop because too short
    (5,  false),
    (7,  true),
    (8,  false),
    (6,  false), // drop because buffer full
    (4,  true),
    (3,  false),
    (5,  false),
    (4,  true), // drop because too many packets
    (6,  false),
    (4,  true),
    (5,  true))

  val outPackets = Seq(
    (4, true),
    (5, false),
    (7, true),
    (8, false),
    (4, true),
    (3, false),
    (5, true),
    (6, true),
    (4, false),
    (5, true))

  val phaseBits = log2Ceil(inPackets.length)
  val idxBits = 4

  val inPacketLengths = VecInit(inPackets.map {
    case (x, _) => (x - 1).U(idxBits.W)
  })
  val inPacketSwitch = VecInit(inPackets.map(_._2.B))

  val outPacketLengths = VecInit(outPackets.map {
    case (x, _) => (x - 1).U(idxBits.W)
  })
  val outPacketSwitch = VecInit(outPackets.map(_._2.B))

  val inPhase = RegInit(0.U(phaseBits.W))
  val outPhase = RegInit(0.U(phaseBits.W))

  val inIdx = RegInit(0.U(idxBits.W))
  val outIdx = RegInit(0.U(idxBits.W))

  val s_start :: s_input :: s_output :: s_done :: Nil = Enum(4)
  val state = RegInit(s_start)

  buffer.io.stream.in.valid := state === s_input
  buffer.io.stream.in.bits.data := inIdx
  buffer.io.stream.in.bits.keep := ~0.U(32.W)
  buffer.io.stream.in.bits.last := inIdx === inPacketLengths(inPhase)
  buffer.io.stream.out.ready := state === s_output

  when (io.start && state === s_start) {
    state := s_input
  }

  when (buffer.io.stream.in.fire()) {
    inIdx := inIdx + 1.U
    when (buffer.io.stream.in.bits.last) {
      inIdx := 0.U
      inPhase := inPhase + 1.U
      when (inPacketSwitch(inPhase)) { state := s_output }
    }
  }

  when (buffer.io.stream.out.fire()) {
    outIdx := outIdx + 1.U
    when (buffer.io.stream.out.bits.last) {
      outIdx := 0.U
      outPhase := outPhase + 1.U
      when (outPacketSwitch(outPhase)) { state := s_input }
      when (outPhase === (outPackets.length - 1).U) { state := s_done }
    }
  }

  assert(!buffer.io.stream.out.valid || buffer.io.stream.out.bits.data === outIdx,
    "NetworkPacketBufferTest: got wrong output data")
  assert(!buffer.io.stream.out.valid || !buffer.io.stream.out.bits.last ||
    outIdx === outPacketLengths(outPhase),
    "NetworkPacketBufferTest: got output packet with wrong length")
  assert(!buffer.io.header.valid || buffer.io.header.bits === (1L << 32).U,
    "NetworkPacketBufferTest: unexpected header")

  io.finished := state === s_done
}

/**
 * [TODO: ReservationBufferAlloc Description]
 *
 * @param nXacts number of transactions
 * @param nWords number of words
 */
class ReservationBufferAlloc(nXacts: Int, nWords: Int) extends Bundle {
  private val xactIdBits = log2Ceil(nXacts)
  private val countBits = log2Ceil(nWords + 1)

  val id = UInt(xactIdBits.W)
  val count = UInt(countBits.W)

  override def cloneType =
    new ReservationBufferAlloc(nXacts, nWords).asInstanceOf[this.type]
}

/**
 * [TODO: ReservationBufferData Description]
 *
 * @param nXacts number of transactions
 * @param netIfWidthBits flit size of network
 */
class ReservationBufferData(nXacts: Int, netIfWidthBits: Int = 64) extends Bundle {
  private val xactIdBits = log2Ceil(nXacts)

  val id = UInt(xactIdBits.W)
  val data = new StreamChannel(netIfWidthBits)

  override def cloneType =
    new ReservationBufferData(nXacts, netIfWidthBits).asInstanceOf[this.type]
}

/**
 * This functional block is used between the reader and the aligner blocks. It is used to make sure that all
 * reads for the packet from the CPU memory are completed and ordered properly (since reads can complete out of order)
 * so that the aligner can recieve a packet in proper order.
 *
 * @param nXacts number of transactions
 * @param nWords number of words
 * @param netIfWidthBits flit size of network
 */
class ReservationBuffer(nXacts: Int, nWords: Int, netIfWidthBits: Int = 64) extends Module {
  private val xactIdBits = log2Ceil(nXacts)
  private val countBits = log2Ceil(nWords + 1)

  require(nXacts <= nWords)

  val io = IO(new Bundle {
    val alloc = Flipped(Decoupled(new ReservationBufferAlloc(nXacts, nWords)))
    val in = Flipped(Decoupled(new ReservationBufferData(nXacts, netIfWidthBits)))
    val out = Decoupled(new StreamChannel(netIfWidthBits))
  })

  def incWrap(cur: UInt, inc: UInt): UInt = {
    val unwrapped = cur +& inc
    Mux(unwrapped >= nWords.U, unwrapped - nWords.U, unwrapped)
  }

  val buffer = Module(new BufferBRAM(nWords, new StreamChannel(netIfWidthBits)))
  val bufValid = RegInit(0.U(nWords.W))

  val head = RegInit(0.U(countBits.W))
  val tail = RegInit(0.U(countBits.W))
  val count = RegInit(0.U(countBits.W))

  val full = (count + io.alloc.bits.count) > nWords.U
  val xactHeads = Reg(Vec(nXacts, UInt(countBits.W)))
  val curXactHead = xactHeads(io.in.bits.id)

  val occupied = RegInit(false.B)
  val ren = (!occupied || io.out.ready) && (bufValid >> tail)(0)

  count := count +
              Mux(io.alloc.fire(), io.alloc.bits.count, 0.U) -
              Mux(io.out.fire(), 1.U, 0.U)
  bufValid := (bufValid | Mux(io.in.fire(), UIntToOH(curXactHead), 0.U)) &
                         ~Mux(ren, UIntToOH(tail), 0.U)

  io.alloc.ready := !full
  io.in.ready := true.B
  io.out.valid := occupied
  io.out.bits := buffer.io.read.data

  buffer.io.write.en := io.in.fire()
  buffer.io.write.addr := curXactHead
  buffer.io.write.data := io.in.bits.data
  buffer.io.read.en := ren
  buffer.io.read.addr := tail

  when (io.alloc.fire()) {
    xactHeads(io.alloc.bits.id) := head
    head := incWrap(head, io.alloc.bits.count)
  }

  when (io.in.fire()) {
    xactHeads(io.in.bits.id) := incWrap(curXactHead, 1.U)
  }

  when (io.out.fire()) { occupied := false.B }

  when (ren) {
    occupied := true.B
    tail := incWrap(tail, 1.U)
  }
}
