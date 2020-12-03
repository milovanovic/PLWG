package plfg

import dsptools.DspTester
import breeze.math.Complex
import chisel3._
import chisel3.util.log2Up
import dsptools.numbers.implicits._
import dsptools.numbers._

/*
* Contains useful helper functions for testers
*/
trait HasPLFGTesterUtil{
  
  def rampPLFG(startingPoint: Int, length: Int, slope: Int): Seq[Int] = {
    val returnVal = new Array[Int](length)
    var i = 0
    var value = startingPoint
    while (i < length) {
      returnVal(i) = value
      value = value + slope
      i +=1
    }
    
    returnVal.toSeq
  }
  
  def constantPLFG(startingPoint: Int, length: Int): Seq[Int] = {
    val returnVal = new Array[Int](length)
    var i = 0
    var value = startingPoint
    while (i < length) {
      returnVal(i) = value
      i +=1
    }
    
    returnVal.toSeq
  }
  
  def trianglePLFG(startingPoint: Int, length1: Int, length2: Int, slope1: Int, slope2: Int): Seq[Int] = {
    val returnVal = new Array[Int](length1+length2)
    var i = 0
    var value = startingPoint
    while (i < length1) {
      returnVal(i) = value
      if(!(i == (length1 - 1)))
        value = value + slope1
      i +=1
    }
    while (i < (length1 + length2)) {
      value = value - slope2
      returnVal(i) = value
      i +=1
    }
    
    returnVal.toSeq
  }

}
