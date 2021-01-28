/*
 (c) Oleg Strygun, 2019
 */

package zhttp.clients.util

import zio.UIO

//import annotation.tailrec
//import java.util.concurrent.atomic.AtomicInteger
//import java.util.concurrent.atomic.AtomicMarkableReference
import java.util.concurrent.atomic.AtomicReference
//import java.lang.ThreadLocal

import scala.collection.mutable.ListBuffer

class ValReference[A](var a: A)

///////////////////////////////////////////////////////////////////////////////
object ValuePair {

  def apply[A, B](key: A, value: B = null)(implicit ord: A => Ordered[A]): ValuePair[A, B] =
    new ValuePair(key, value)
}

///////////////////////////////////////////////////////////////////////////////
object ReversedValuePair {

  def apply[A, B](key: A, value: B = null)(implicit ord: A => Ordered[A]): ReversedValuePair[A, B] =
    new ReversedValuePair(key, value)
}

///////////////////////////////////////////////////////////////////////////////
object StringIgnoreCaseValuePair {

  def apply[B](key: String, value: B = null): StringIgnoreCaseValuePair[B] =
    new StringIgnoreCaseValuePair(key, value)
}

////////////////////////////////////////////////////////////////////////////////
class ValuePair[A, B](val key: A, val value: B) (implicit ord: A => Ordered[A]) extends Ordered[ValuePair[A, B]] {

  override def compare(that: ValuePair[A, B]): Int =
    key.compare(that.key)

}

////////////////////////////////////////////////////////////////////////////////
class ReversedValuePair[A, B](val key: A, val value: B)(implicit ord: A => Ordered[A])
    extends Ordered[ReversedValuePair[A, B]] {

  override def compare(that: ReversedValuePair[A, B]): Int =
    key.compare(that.key) * -1

}

////////////////////////////////////////////////////////////////////////////////
class StringIgnoreCaseValuePair[B](val key: String, val value: B) extends Ordered[StringIgnoreCaseValuePair[B]] {

  override def compare(that: StringIgnoreCaseValuePair[B]): Int = {

    //we preserve original case, but for all subsequent finds or adds letter case will be ignored

    val nocaseKey      = key.toLowerCase();
    val nocase_thatKey = that.key.toLowerCase();

    nocaseKey.compare(nocase_thatKey)

  }
}

////////////////////////////////////////////////////////////////////////////////
class ReversedStringIgnoreCaseValuePair[B](val key: String, val value: B)
    extends Ordered[ReversedStringIgnoreCaseValuePair[B]] {

  override def compare(that: ReversedStringIgnoreCaseValuePair[B]): Int = {

    //we preserve original case, but for all subsequent finds or adds letter case will be ignored

    val nocaseKey      = key.toLowerCase();
    val nocase_thatKey = that.key.toLowerCase();

    nocaseKey.compare(nocase_thatKey) * -1

  }
}

/////////////////////////////////////////////////////////////////////////////////////
class MultiValuePair[A, B](val key: A, val value: B)(implicit ord: A => Ordered[A])
    extends Ordered[MultiValuePair[A, B]] {

  //fancy comparison to support multivalues/same keys, if you make an attempt to add a key/value pair which key is present but value is different, it will be added again
  //if you want to remove it, you need exact key and value pair provided
  override def compare(that: MultiValuePair[A, B]): Int = {
    val res = key.compare(that.key)

    if (res == 0) {
      if (value == that.value) 0 //find or prevent duplicate or remove only if value matches requested value.
      else 1
    } else res

  }
}

/////////////////////////////////////////////////////////////////////////////////////
class MultiValuePairReversed[A, B](val key: A, val value: B)(implicit ord: A => Ordered[A])
    extends Ordered[MultiValuePairReversed[A, B]] {

  //fancy comparison to support multivalues/same keys, if you make an attempt to add a key/value pair which key is present but value is different, it will be added again
  //if you want to remove it, you need exact key and value pair provided
  override def compare(that: MultiValuePairReversed[A, B]): Int = {
    val res = key.compare(that.key) * -1

    if (res == 0) {
      if (value == that.value) 0 //find or prevent duplicate or remove only if value matches requested value.
      else 1
    } else res

  }
}

/////////////////////////////////////////////////////////////////////////////////////
//Multvalue pair sorted by key and value - its Map key and value key
class MultiValuePairOrd[A, B](val key: A, val value: B, val reversed_values: Boolean = false)(
  implicit ord: A => Ordered[A],
  ord2: B => Ordered[B]
) extends Ordered[MultiValuePairOrd[A, B]] {

  //fancy comparison to support multivalues/same keys, if you make an attempt to add a key/value pair which key is present but value is different, it will be added again
  //if you want to remove it, you need exact key and value pair provided
  override def compare(that: MultiValuePairOrd[A, B]): Int = {

    val mult = if (reversed_values) -1 else 1

    val res = key.compare(that.key)

    if (res == 0) {
      value.compare(that.value) * mult
    } else res

  }
}

/////////////////////////////////////////////////////////////////////////////////////
//Multvalue pair sorted by key and value - its Map key and value key
class MultiValuePairReversedOrd[A, B](val key: A, val value: B, val reversed_values: Boolean = false)(
  implicit ord: A => Ordered[A],
  ord2: B => Ordered[B]
) extends Ordered[MultiValuePairReversedOrd[A, B]] {

  /////////////////////////////////////////////////////////////////////
  override def compare(that: MultiValuePairReversedOrd[A, B]): Int = {

    val mult = if (reversed_values) -1 else 1

    val res = key.compare(that.key) * -1

    if (res == 0) {
      value.compare(that.value) * mult
    } else res

  }
}

/////////////////////////////////////////////////////////////////////////////
//Taking same idea further, using two keys and value sorted map
//practical example can be: username, userattribute, value
class TwoKeysMultiValuePairOrd[A1, A2, B](val key1: A1, val key2: A2, val value: B)(
  implicit ord1: A1 => Ordered[A1],
  ord2: A2 => Ordered[A2],
  ord3: B => Ordered[B]
) extends Ordered[TwoKeysMultiValuePairOrd[A1, A2, B]] {

  ///////////////////////////////////////////////////////////////////////////
  override def compare(that: TwoKeysMultiValuePairOrd[A1, A2, B]): Int = {

    //key1 first priority, if it's the same we compare key2, if key2 is the same we compare value
    val res = key1.compare(that.key1)
    if (res == 0) {
      val res1 = key2.compare(that.key2)
      if (res1 == 0) {
        value.compare(that.value)
      } else res1
    } else res

  }
}

//////////////////////////////////////////////////////////////////////////////////
class SkipList[A](implicit ord: A => Ordered[A]) {

  var FACTOR = 2

  //MARKERS
  //static sentinel nodes, we share all the last nodes across all the layers
  //if we didn't do so, it would be impossible to provide atomic updates on new layer addition

  //_lastRef marker, refers to itself for a bottom layer
  val _lastRef: NodeRef[A] = new LastRef[A]()
  _lastRef.ref = _lastRef

  //constructs empty vals
  val vals = Node.constructEmpty[A](_lastRef)

  //constructs empty layer, it shares previously initialized_lastRef marker and sets the bottom reference ("ref") of first element to vals
  private val top: AtomicReference[NodeRef[A]] = new AtomicReference(
    NodeRef.constructEmptyEx[A](external_next_last = _lastRef, ref_first = vals)
  )

  def head: A = vals.getReference().a

  ////////////////////////////////////////////////////////////////////
  def debug_test_ref(list: Node[A], ref: Node[A]): Boolean = {
    var result = false
    OrderedList.find[A](list, (c: Node[A]) => {
      if (c.eq(ref)) {
        result = true
      } else {
        if (c.a == ref.a) {
          println("REF DIFFERENT but value the same")
        }
        result = false
      }
      result
    })
    if (result == false) {
      val marked: Array[Boolean] = Array(false)
      if (ref.getOrig != null)
        ref.getOrig.get(marked)
      else println("orig null ")
      println("NOT THERE=" + ref.a + " orig= " + ref.getOrig.a + "with marked = " + marked(0))
    }
    result
  }

  /////////////////////////////////////////////////////////////////
  def debug_validate(): Boolean = {

    var status            = true
    var curLayer: Node[A] = top.get()

    while (curLayer.hasRef) {

      val bottomLayer = curLayer.getRef

      var curTop = curLayer.getReference

      while (curTop.isLast == false && status == true) {
        if (debug_test_ref(bottomLayer, curTop.getRef) == false) {
          status = false
        }
        curTop = curTop.getReference()
      }

      if (status == false) {
        println(Thread.currentThread().getId().toString + " for Layer:" + curLayer + " FAILED")
        return false
      }
      println(Thread.currentThread().getId().toString + " for Layer:" + curLayer + " OK")

      curLayer = curLayer.getRef

    }

    status
  }

  import scala.collection.mutable.StringBuilder

  ////////////////////////////////////////////////////////////////////
  def debug_print(out: StringBuilder, top1: Node[A] = top.get()): StringBuilder = {

    out.append(top1.toString + " > ")

    OrderedList.foreach[A](top1, c => {
      out.append(c);
      out.append(",")
    })
    out.append("\n")
    if (top1.hasRef) {
      debug_print(out, top1.getRef)
    }

    out
  }

  //////////////////////////////////////////////////////////////////////
  def debug_print_layers(out: StringBuilder) = {

    var cur: Node[A] = top.get();
    var ln           = 0;

    while (cur.hasRef) {
      out.append("Layer " + ln + " - " + OrderedList.count[A](cur) + "\n")
      ln = ln + 1
      cur = cur.getRef
    }

    out.append("Bottom - " + OrderedList.count[A](cur) + "\n")

  }

  /////////////////////////////////////////////////////////////////////
  def isEmpty(from: Node[A]): Boolean =
    from.isFirst && from.getReference.isLast

  /////////////////////////////////////////////////////////////////////
  def removeLayer(ref: Node[A]): Boolean = {

    //println("Remove")
    val old_top = top.get()
    val new_top = ref

    if (new_top.hasRef == true) {
      top.compareAndSet(old_top, new_top.asInstanceOf[NodeRef[A]])
      true
    } else
      false
  }

  /////////////////////////////////////////////////////////////////////
  def count(): Int =
    OrderedList.count[A](vals)


  //////////////////////////////////////////////////////////////////////
  
  def u_get( a : A ) : UIO[Option[A]] = UIO( get(a) )

  //////////////////////////////////////////////////////////////////////
  def get(a: A): Option[A] = { 
    val list = findClosestLT(a).getReference

    if (list.isLast || list.isFirst) None
    else if (a.compareTo(list.a) == 0) Some(list.a)
    else None
  }

  //////////////////////////////////////////////////////////////////////
  def find(a: A): Boolean = {
    val list = findClosestLT(a)

    if (a.compareTo(list.getReference.a) == 0) true
    else false
  }

  ////////////////////////////////////////////////////////////////////
  def findClosestLesser(a: A): Option[A] = {
    val list = findClosestLT(a)
    if (list.isFirst || list.isLast) None
    else Some(a)
  }

  //////////////////////////////////////////////////////////////////////
  def foreach(p: A => Unit, While: A => Boolean = (_) => true, Filter: A => Boolean = (_) => false): Unit =
    foreachFrom(head, p, While, Filter)

  //////////////////////////////////////////////////////////////////////
  def foreachFrom(
    From: A,
    p: A => Unit,
    While: A => Boolean = (_) => true,
    Filter: A => Boolean = (_) => false
  ): Unit = {

    val list = findClosestLT(From)

    val marked = Array[Boolean](false)
    var curr   = list.getReference()
    var drop   = false

    while (!curr.isLast && drop == false) {
      if (!curr.isFirst && marked(0) == false) {
        drop = !While(curr.a)
        if (drop == false)
          if (Filter(curr.a) == false) p(curr.a)
      }
      curr = curr.get(marked) //we don't check first element if marked, the rest are just duplicated extra top level checks in an attempt to provide the most recent version
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  def copyRange(From: A, While: A => Boolean, Filter: A => Boolean = (_) => true): Seq[A] = {

    val res = ListBuffer[A]()
    val p   = (a: A) => { res += a; () }

    foreachFrom(From, p, While, Filter)

    res.toSeq

  }

  ///////////////////////////////////////////////////////////////////////////
  //deprecated - TODO switch to scala LazyList
  /*
   def toStream( a : A ) : Stream[A] =
   {
      val head = findClosestLT( a ).getReference

      def fromNode( from : Node[ A ]) : Stream[A] =
      {
         val marked = Array[Boolean](false)

         var next = from
         do {
            next = next.get( marked )
         }  while(  marked(0) == true )

          next match {
             case  `_lastRef` => Stream.empty
             case  _          => next.a #:: fromNode( next)
          }

      }

      val stream : Stream[A] = head.a #:: fromNode( head )

      stream

   } */

  ///////////////////////////////////////////////////////////////////////////
  private def findClosestLT(a: A): Node[A] = {

    val res = Array[Node[A]](null)

    var status = false

    while (status == false) {
      status = _findClosestLT(a, top.get(), _lastRef, res)
    }

    res(0)
  }

  //////////////////////////////////////////////////////////////////////////
  private def _findClosestLT(a: A, from: Node[A], to: Node[A], res: Array[Node[A]]): Boolean = {

    val closestTop = OrderedList.findClosestLesserValue2(from, from, null, a)

    if (closestTop == null) {
      false
    } else if (from.hasRef == false) {
      res(0) = closestTop
      true

    } else {
      _findClosestLT(a, closestTop.getRef, closestTop.getReference.getRef, res)
    }
  }

  ///////////////////////////////////////////////////////////////////
  final def add(a: A): UIO[Boolean] = {

    val newTopRef = Array[Node[A]](null)
    val origRef   = Array[Node[A]](null)
    val added     = Array[Boolean](false)

    val repeat_add = for {
      _      <- UIO(newTopRef(0) = null)
      status <- _add(top.get(), _lastRef, newTopRef, origRef, added, a)

    } yield (status)

    repeat_add.repeatWhile(_ == false) *>
      UIO {
        if (newTopRef(0) != null) {
          val old_top = top.get()
          if (isEmpty(old_top) == false) {
            val new_top = NodeRef.constructEmptyEx[A](external_next_last = _lastRef, ref_first = old_top)
            top.compareAndSet(old_top, new_top) //no issues if we could not do that, some other parallel thread did
          }

        }
      } *>
      UIO(added(0))

  }

  ///////////////////////////////////////////////////////////////////
  final def remove(a: A): UIO[Boolean] = {
    var cntr = 0;
    //status variable for recursion
    val merge       = Array[Boolean](false) //merge means that we need to remove one item from upper layer
    val colapse     = Array[Boolean](false) //colapse means that one of the upper layers don't have members anymore and we cut it off and all the preceding higher layers
    val removed     = Array[Boolean](false) //flag when somthing was removed from the list, it's possible to call function many times for not present items, so it can be false
    val removeFinal = Array[Boolean](false)
    val removedFrom = Array[Node[A]](null)
    val layerOnly   = Array[Boolean](false)
    val aa          = new ValReference(a)
    val newSplit    = Array[Node[A]](null)

    var result_removed_or_not: Boolean = false

    var status1 = false

    val rm_io = _remove(
      top.get(),
      _lastRef,
      aa,
      merge,
      removed,
      removeFinal = removeFinal,
      removedFrom,
      colapse,
      layerOnly,
      newSplit
    ).map(status => {

      merge(0) = false
      colapse(0) = false
      removed(0) = false
      removedFrom(0) = null
      if (removeFinal(0) == true) result_removed_or_not = true
      status
    })

    rm_io.repeatWhile(_ == false) *>
      UIO {
        if (newSplit(0) != null) {

          val old_top = top.get()

          if (isEmpty(old_top) == false) {

            val new_top = NodeRef.constructEmptyEx[A](external_next_last = _lastRef, ref_first = old_top)

            top.compareAndSet(old_top, new_top) //no issues if we could not do that, some other parallel thread did
          }
        }

        result_removed_or_not
      }

  }

  //////////////////////////////////////////////////////////////////
  private def _add(
    from: Node[A],
    to: Node[A],
    newTopRef: Array[Node[A]],
    origRef: Array[Node[A]],
    added: Array[Boolean],
    a: A
  ): UIO[Boolean] = {
    val marked = Array[Boolean](false)
    val count  = Array[Int](0)

    if (from.hasRef == false) {
      UIO {
        origRef(0) = null
        newTopRef(0) = null
        val added_loc = Array[Boolean](false)
        val status =
          OrderedList.insertInRange(from, from, to, Node(a, null), count, FACTOR, newTopRef, added_loc, false)

        origRef(0) = newTopRef(0)
        added(0) = added_loc(0)

        status
      }
    } else {
      for {
        closestTop <- UIO(OrderedList.findClosestLesserValue2(from, from, null, a))
        status1 <- if (closestTop == null) UIO(false)
                  // val lowerNodeFrom = closestTop.getRef
                  //val lowerNodeTo = closestTop.getReference.getRef
                  else _add(closestTop.getRef, closestTop.getReference.getRef, newTopRef, origRef, added, a)

        status <- if (status1 == true && newTopRef(0) != null) {
                   UIO {
                     val newNode =
                       if (newTopRef(0).hasRef == false)
                         NodeRef(newTopRef(0).a, newTopRef(0), null, origRef(0))
                       else
                         NodeRef(newTopRef(0).a, newTopRef(0), null, newTopRef(0).getOrig)

                     newTopRef(0) = null
                     count(0) = 0
                     val added_layer = Array[Boolean](false)
                     val status =
                       OrderedList.insertInRange(from, from, to, newNode, count, FACTOR, newTopRef, added_layer, false)

                     status
                   }
                 } else UIO(status1)

      } yield (status)

    }
  }

  private def ref_remove(
    from: Node[A],
    to: Node[A],
    al: ValReference[A],
    merge: Array[Boolean],
    remove: Array[Boolean],
    removeFinal: Array[Boolean],
    removeFrom: Array[Node[A]],
    colapse: Array[Boolean],
    layerOnly: Array[Boolean],
    newSplit: Array[Node[A]]
  ): UIO[Boolean] = {
    val predValueToClosestLesser = Array[Node[A]](null)
    val count                    = Array[Int](0)

    var prev_removedFrom: Node[A]     = null
    var prev_merge_requested: Boolean = false
    var prev_split: Node[A]           = null

    for {
      closestTop <- UIO(OrderedList.findClosestLesserValue2(from, from, predValueToClosestLesser, al.a))
      status2 <- if (closestTop != null && closestTop.isLast == false) {

                  val lowerNodeFrom = closestTop.getRef
                  val lowerNodeTo   = closestTop.getReference.getRef
                  merge(0) = false
                  remove(0) = false
                  removeFrom(0) = null
                  newSplit(0) = null

                  _remove(
                    lowerNodeFrom,
                    lowerNodeTo,
                    al,
                    merge,
                    remove,
                    removeFinal,
                    removeFrom,
                    colapse,
                    layerOnly,
                    newSplit
                  )

                } else UIO(false)

      status3 <- if (status2 == true && colapse(0) == false) {

                  prev_removedFrom = removeFrom(0) //if item wasn't removed ( not found ), removeFrom will have closest node anyway, so we can recover.
                  prev_merge_requested = merge(0)

                  prev_split = newSplit(0)

                  merge(0) = false
                  remove(0) = false
                  count(0) = 0
                  removeFrom(0) = null
                  newSplit(0) = null

                  UIO(
                    OrderedList.removeFromRange(
                      from,
                      from,
                      to,
                      al.a,
                      count,
                      FACTOR,
                      merge,
                      remove,
                      removeFrom,
                      done = false,
                      abort = false,
                      newSplit
                    )
                  )

                } else if (colapse(0) == true) UIO(true)
                else (UIO(false))

      status4 <- if (status3) {

                  if (prev_split != null) {
                    count(0) = 0
                    val newNode =
                      if (prev_split.hasRef == false) NodeRef(prev_split.a, prev_split, null, prev_split)
                      else NodeRef(prev_split.a, prev_split, null, prev_split.getOrig)
                    val added2 = Array[Boolean](false)
                    OrderedList.insertInRange(
                      from,
                      from,
                      to,
                      newNode,
                      count,
                      FACTOR,
                      new Array[Node[A]](1),
                      added2,
                      false
                    )
                  } else {
                    if (isEmpty(from)) {
                      if (removeLayer(from.getRef)) colapse(0) = true
                    }
                  }

                  if (colapse(0) == false && prev_merge_requested == true)
                    UIO(merge_layer(closestTop, al, removeFrom, layerOnly, colapse, predValueToClosestLesser))
                  else
                    UIO(true)

                } else UIO(false)

    } yield (status4)

  }

  ///////////////////////////////////////////////////////////////////////
  private def _remove(
    from: Node[A],
    to: Node[A],
    al: ValReference[A],
    merge: Array[Boolean],
    remove: Array[Boolean],
    removeFinal: Array[Boolean],
    removeFrom: Array[Node[A]],
    colapse: Array[Boolean],
    layerOnly: Array[Boolean],
    newSplit: Array[Node[A]]
  ): UIO[Boolean] = {

    val count = Array[Int](0)

    if (from.hasRef == false) {
      UIO {
        merge(0) = false
        remove(0) = false
        count(0) = 0
        val status = if (layerOnly(0) == false) {
          val temp = OrderedList.removeFromRange(
            from,
            from,
            to,
            al.a,
            count,
            FACTOR,
            merge,
            remove,
            removeFrom,
            done = false,
            abort = false,
            newSplit
          )
          //println( "----------------->" + remove(0) + " status=" + temp )
          //preserve info if real remove happened in the data layer
          removeFinal(0) = remove(0)
          temp
        } else true

        status
      }

    } else {

      ref_remove(from, to, al, merge, remove, removeFinal, removeFrom, colapse, layerOnly, newSplit)

    }
  }

  private def merge_layer(
    closestTop: Node[A],
    al: ValReference[A],
    removeFrom: Array[Node[A]],
    layerOnly: Array[Boolean],
    colapse: Array[Boolean],
    predValueToClosestLesser: Array[Node[A]]
  ): Boolean = {
    var status: Boolean = false

    removeFrom(0) = null

    if (!closestTop.getReference.isLast) {
      val lostVal = closestTop.getReference().a
      al.a = lostVal
      layerOnly(0) = true

      if (OrderedList.removeNext(closestTop) == false) {
        status = false
      }
      //after we removed, check again
      if (closestTop.isFirst && closestTop.getReference.isLast) {
        status = true
        //println("3Remove empty layer for:" + al.a)
        if (removeLayer(closestTop.getRef))
          colapse(0) = true
      }
    } else {
      if (closestTop.isFirst == true) {
        //println("Remove empty layer for:" + al.a)
        if (removeLayer(closestTop.getRef))
          colapse(0) = true
      } else if (predValueToClosestLesser(0) == null) {
        status = true
      } else {
        val lostVal = predValueToClosestLesser(0).getReference().a

        al.a = lostVal
        layerOnly(0) = true

        if (OrderedList.removeNext(predValueToClosestLesser(0)) == false) {
          status = false
        }

        if (predValueToClosestLesser(0).isFirst) {
          status = true
          //println("2Remove empty layer for:" + al.a)
          if (removeLayer(predValueToClosestLesser(0).getRef))
            colapse(0) = true
        }

      }
    }
    status
  }

}
