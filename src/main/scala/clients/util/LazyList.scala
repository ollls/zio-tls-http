/*
 (c) Oleg Strygun, 2019
 */

package zhttp.clients.util

import java.util.concurrent.atomic.AtomicMarkableReference
import annotation.tailrec
import math.Ordered.orderingToOrdered

object Node {

  /////////////////////////////////////////////////
  def apply[A](a: A, next: Node[A])(implicit ord: A => Ordered[A]): Node[A] =
    new Node[A](a, next)

  /////////////////////////////////////////////////
  def constructEmpty[A](last: Node[A])(implicit ord: A => Ordered[A]): Node[A] =
    new First[A](last)
}

object NodeRef {

  /////////////////////////////////////////////////
  def apply[A](a: A, ref: Node[A], next: Node[A], orig: Node[A] = null)(implicit ord: A => Ordered[A]): Node[A] =
    new NodeRef[A](a, ref, next, orig)

  /////////////////////////////////////////////////
  def constructEmpty[A](ref_first: Node[A] = null, ref_last: Node[A] = null)(
    implicit ord: A => Ordered[A]
  ): NodeRef[A] =
    new FirstRef[A](new LastRef[A](ref_last), ref_first)

  /////////////////////////////////////////////////
  def constructEmptyEx[A](external_next_last: Node[A], ref_first: Node[A] = null)(
    implicit ord: A => Ordered[A]
  ): NodeRef[A] =
    new FirstRef[A](external_next_last, ref_first)
}

//has only a
class Node[A](var a: A = null.asInstanceOf[A], next: Node[A])(implicit ord: A => Ordered[A])
    extends AtomicMarkableReference[Node[A]](next, false)
    with Ordered[Node[A]] {

  //Node as Ordered is not used in that file, but this is very convenient for other implementations, skip lists, etc ...
  override def compare(that: Node[A]): Int =
    a.compare(that.a)

  //we compare node with just a value A, we override lt() to make sentinel nodes with the biggest and smallest values respectively
  def lt(a: A): Boolean = this.a < a

  def isFirst = false

  def isLast = false

  def hasRef = false

  def getRef: Node[A] = null

  def getOrig: Node[A] = null

}

//Node which support additional reference, cannot not be updated for lifecycle of the node
//can be used with insertInOrder(),needs to be created outside and cast to base class Node
//has a and extra ref
class NodeRef[A](a: A = null.asInstanceOf[A], var ref: Node[A], next: Node[A], val orig: Node[A])(
  implicit ord: A => Ordered[A]
) extends Node[A](a, next) {

  override def hasRef = true

  override def getRef: Node[A] = ref

  override def getOrig: Node[A] = orig

}

//generic sentinel nodes, First: smallest value node, and Last: greatest value node
private class FirstRef[A](next: Node[A], ref: Node[A] = null, orig: Node[A] = null)(implicit ord: A => Ordered[A])
    extends NodeRef[A](null.asInstanceOf[A], ref, next, orig) {
  override def compare(that: Node[A]): Int = -1 //always smaller of the smallest

  override def lt(a: A): Boolean = true //never less then any value

  override def isFirst = true

  override def isLast = false

}

class LastRef[A](ref: Node[A] = null)(implicit ord: A => Ordered[A])
    extends NodeRef[A](null.asInstanceOf[A], ref, null, null) {
  override def compare(that: Node[A]): Int = 1 //bigest ever

  override def lt(a: A): Boolean = false

  override def isFirst = false

  override def isLast = true

}

//generic sentinel nodes, First: smallest value node, and Last: greatest value node
private class First[A](next: Node[A])(implicit ord: A => Ordered[A]) extends Node[A](null.asInstanceOf[A], next) {
  override def compare(that: Node[A]): Int = -1 //always smaller of the smallest

  override def lt(a: A): Boolean = true //never less then any value

  override def isFirst = true

  override def isLast = false
}

class Last[A](implicit ord: A => Ordered[A]) extends Node[A](null.asInstanceOf[A], null) {
  override def compare(that: Node[A]): Int = 1 //bigest ever

  override def lt(a: A): Boolean = false

  override def isFirst = false

  override def isLast = true

}

object OrderedList {

  /*
  /////////////////////////////////////////////////
  def apply[A](as: A*)
              (implicit ord: A => Ordered[A]): Node[A] = {
    val list = new First[A](new Last[A])
    var cur = as

    while (!cur.isEmpty) {
      insertInOrder(list, Node(cur.head))
      cur = cur.tail
    }
    list
  }*/

  ////////////////////////////////////////////////
  def count[A](list: Node[A]): Integer = {
    var count = 0;
    foreach(list, (p: A) => count = count + 1)
    count
  }

 
  ////////////////////////////////////////
  def countRange[A](from: Node[A], to: Node[A]): Integer = {

    var curr   = from
    var count  = 0;
    val marked = Array[Boolean](false)

    curr = curr.get(marked)

    while (!curr.isLast && curr != to) {
      if ( marked(0) == false ) count += 1
      curr = curr.get(marked)
    }

    count
  }

  //////////////////////////////////////////////
  def foreach[A](list: Node[A], p: (A) => Unit): Unit = {
    val marked = Array[Boolean](false)
    var curr   = list
    curr.get(marked)
    while (!curr.isLast) {
      if (!curr.isFirst && marked(0) == false) p(curr.a)
      curr = curr.get(marked)
    }
  }

  //////////////////////////////////////////////
  def find[A](list: Node[A], p: (Node[A]) => Boolean): Boolean = {
    var result = false
    val marked = Array[Boolean](false)
    var curr   = list
    curr.get(marked)
    while (!curr.isLast && result == false) {
      if (!curr.isFirst && marked(0) == false) {
        if (p(curr) == true) result = true
      }
      curr = curr.get(marked)
    }

    result
  }



  /////////////////////////////////////////////////////////////////////
  @tailrec
  def removeNext2[A](list: Node[A], removedNode : Array[Node[A]] ): Boolean = {
    val marked = Array[Boolean](false)

    val pred = list
    val curr = pred.get(marked)

    removedNode(0) = null

    //marked(0) = true;

    if (marked(0) == false) {

      val succ = curr.getReference

      if (curr.isLast) return true //nothing to remove but true

      if (curr.compareAndSet(succ, succ, false, true) == false) {
        list.get(marked)
        if (marked(0) == false) {
          removeNext2(list, removedNode )
        } else {
          false
        }
      } else {
        val res = pred.compareAndSet(curr, succ, false, false)
        if ( res == true ) removedNode( 0 ) = curr
        res
      }
    } else false
  }

  /////////////////////////////////////////////////////////////////////
  @tailrec
  def removeNext[A](list: Node[A]): Boolean = {
    val marked = Array[Boolean](false)

    val pred = list
    val curr = pred.get(marked)

    //marked(0) = true;

    if (marked(0) == false) {

      val succ = curr.getReference

      if (curr.isLast) return true //nothing to remove but true

      if (curr.compareAndSet(succ, succ, false, true) == false) {
        list.get(marked)
        if (marked(0) == false) {
          removeNext(list)
        } else {
          false
        }
      } else {
        pred.compareAndSet(curr, succ, false, false)
      }
    } else false
  }

  ////////////////////////////////////////////////////////////////////////
  //we need to preserve - start node, to be able to retry recursively
  @tailrec
  def findClosestLesserValue2[A](list: Node[A], repeat: Node[A], pred2: Array[Node[A]], a: A)(implicit ord: A => Ordered[A]): Node[A] = {

    if (list.isLast) return null

    val curr_marked = Array[Boolean](false)
    val marked2     = Array[Boolean](false)
    val marked3     = Array[Boolean](false)
    val pred        = list

    val curr = pred.getReference()

    val Node_a = Node(a, null) 
   // if ( list.isFirst == false && Node_a.lt( list.a )) return null

    if (curr == null) {

      return null
    }

    //marked for curr
    val succ = curr.get(curr_marked)
    //marked2 for orig curr

    /////////////////////////////////////////////////
    val orig = curr.getOrig
    if (orig != null)
      orig.get(marked2)

    ////////////////////////////////////////////////
    if (curr.hasRef) {
      val ref = curr.getRef
      ref.get(marked3)

    }

    ////////////////////////////////////////////////

    if (marked2(0) == true || marked3(0) == true) {
      //println( "orig deleted " + a )
      curr.compareAndSet(succ, succ, false, true)

      //println( "orig deleted success")
      curr_marked(0) = true
    }

    if (curr_marked(0) == true && (pred.compareAndSet(curr, succ, false, false) == false)) {
      val marked = Array[Boolean](false)
      //println( "findClosestLesserValue2 repeat")
      repeat.get(marked)
      //start node, is it still there ?
      if (marked(0) == false) {
        findClosestLesserValue2(repeat, repeat, pred2, a) //repeat the whole thing from start node
      } else {
        /*println( "findClosestLesserValue2 - cannot repeat" ); */
        null
      }
    }
    //cur less then value we check, or value we check greater then node's values, will keep looking
    //corner case, when empty: here pred.getReference() will be Last, last's lt() always false, we will return after pred, which was First and before Las
    else if (curr.lt(a)) {
      if (pred2 != null) pred2(0) = pred;
      findClosestLesserValue2(curr, repeat, pred2, a)
    }         //advance one more cur and succ, preserve initial or start node
    else pred //insert after this
  }

  //////////////////////////////////////////////////////////////////////////////
  @tailrec
  def insertInRange[A](
    start: Node[A],
    from: Node[A],
    to: Node[A],
    new_n: Node[A],
    count: Array[Int],
    factor: Int,
    newTo: Array[Node[A]],
    added: Array[Boolean],
    done: Boolean
  ): Boolean = {
    val marked  = Array[Boolean](false)
    val marked2 = Array[Boolean](false)
    val pred    = from

    to.get(marked)
    if (marked(0) == true) return false

    if (pred.eq(to) == false) {

      //we went out of limit, preserve range border, if we reach twice as much items, we will spawn a new range
      if (count(0) == factor) {
        newTo(0) = pred
      }

      val curr = pred.getReference()

      val succ = if (curr != null) curr.get(marked) else return false

      //marked2 for orig curr
      val orig = curr.getOrig
      if (orig != null)
        orig.get(marked2)

      //process lazy delete
      if (marked(0) == true && (pred.compareAndSet(curr, succ, false, false) == false)) {
        //full repeat
        from.get(marked)
        if (marked(0) == true) //special case, first element no more, cannot run usual repeat
          {
            //abort, rewind to "to"
            insertInRange(start, to, to, new_n, count, factor, newTo, added, false)
          } else {
          count(0) = 0
          insertInRange(start, start, to, new_n, count, factor, newTo, added, false)
        }
      } else if (done == true) //check if we already inserted something but still counting the range
        {
          count(0) = count(0) + 1
          insertInRange(start, curr, to, new_n, count, factor, newTo, added, true)
        } else if (curr.lt(new_n.a)) //counting, but we did not find a place to insert yet
        {
          count(0) = count(0) + 1
          insertInRange(start, curr, to, new_n, count, factor, newTo, added, false)
        } else {
        if ((curr.isLast) || (new_n.compareTo(curr) != 0)) {
          //if (new_n.a != curr.a) { // equality is not properly defined, we need to rely on Ordered for comparisons but we need to exclude sentinel nodes
          //process add/insert
          new_n.set(curr, false)
          if (pred.compareAndSet(curr, new_n, false, false) == false) {
            start.get(marked)
            if (marked(0) == true) //special case, first element no more, cannot run usual repeat
              {
                //drop, global repeat
                insertInRange(start, to, to, new_n, count, factor, newTo, added, false)
              } else {
              count(0) = 0
              insertInRange(start, start, to, new_n, count, factor, newTo, added, false)
            }
          } else {
            added(0) = true
            count(0) = count(0) + 2 //current and the new
            //skip recently added and point to the added one, one before current
            insertInRange(start, new_n, to, new_n, count, factor, newTo, added, true) //mark as done but keep counting
          }
        } else
          insertInRange(start, curr, to, new_n, count, factor, newTo, added, done = true) //ignore dupe, counting with done = true
      }
    } else if (done == true) {
      //end processing, we reached "to" with good status
      if (count(0) < 2 * factor) {
        //less then twice as much items in single range, no need to create a new range, erase preseved value
        newTo(0) = null
      }
      true
    } else done //will be false - for drop cases, all other cases: it cannot reach the end and be false
  }

  //////////////////////////////////////////////////////////////////////////////
  //we use extra abort flag, to show the difference between removal of non-existing item and failed removal
  def removeFromRange[A](
    start: Node[A],
    from: Node[A],
    to: Node[A],
    a: A,
    count: Array[Int],
    factor: Int,
    merge: Array[Boolean],
    remove: Array[Boolean],     //element was removed from the range
    removeFrom: Array[Node[A]], //can be null if removed after initial/start node
    done: Boolean,              //if item not found, done false, return status true
    abort: Boolean,
    newSplit: Array[Node[A]]
  )(implicit ord: A => Ordered[A]): Boolean = {

    val Node_a = Node(a, null) //for gen cases we cannot use "==", just reuse Ordered by applying Node as wrapper

    
    if ( start.isFirst == false && Node_a.lt( start.a ) ) { return false }
    if ( to.isLast == false && to.lt( a )) { return false }

    val status = removeFromRange_t(start, from, to, Node_a, count, factor, merge, remove, removeFrom, done, abort, newSplit)

    if (remove(0) == true) {
      if (count(0) < factor / 2) merge(0) = true
      else merge(0) = false
    }
    if (count(0) > factor * 10 || count(0) < 2 * factor) {
      newSplit(0) = null //range too small, hide split
    }
    status
  }

//////////////////////////////////////////////////////////////////////////////
  //we use extra abort flag, to show the difference between removal of non-existing item and failed removal
  @tailrec
  private def removeFromRange_t[A](
    start: Node[A],
    from: Node[A],
    to: Node[A],
    Node_a: Node[A],
    count: Array[Int],
    factor: Int,
    merge: Array[Boolean],
    remove: Array[Boolean],     //element was removed from the range
    removeFrom: Array[Node[A]], //can be null if removed after initial/start node
    done: Boolean,              //if item not found, done false, return status true
    abort: Boolean,
    newSplit: Array[Node[A]]
  )(implicit ord: A => Ordered[A]): Boolean = {

    val marked = Array[Boolean](false)
    val pred   = from
    val curr   = pred.getReference

    if ( pred.isLast ) return false

    if (count(0) > factor * 10) return false

    if (pred.eq(to) == false && curr.isLast == false) {

      if (count(0) == factor) newSplit(0) = pred

      val succ = curr.get(marked)

      if (marked(0) == true || done == true) {
        val marked_s = Array[Boolean](false)

        from.get(marked_s)
        if (marked_s(0) == true) return false

        if (marked(0)) pred.compareAndSet(curr, succ, false, false)

        if (marked(0) == false) count(0) = count(0) + 1

        removeFromRange_t(start, curr, to, Node_a, count, factor, merge, remove, removeFrom, done, false, newSplit)
      } else {
        /* COMPARE */
        if (curr.compareTo( Node_a ) == 0) {
          remove(0) = curr.compareAndSet(succ, succ, false, true)
          if (remove(0) == true) {

            pred.compareAndSet(curr, succ, false, false)

            count(0) = count(0) - 1
            if (start.eq(pred) == false)
              removeFrom(0) = pred //save the node before removed one. we may need it for substitution in case if node was removed from reference layers
            else
              removeFrom(0) = null //nothing to give, pred is very first already, we cannot be out of bound

            if (curr.eq(to) == false && curr.isLast == false)
              removeFromRange_t(
                start,
                curr.getReference(),
                to,
                Node_a,
                count,
                factor,
                merge,
                remove,
                removeFrom,
                done = true,
                false,
                newSplit
              )
            else true
          } else {
            count(0) = 0;
    
            return false
          }
        } else {
          count(0) = count(0) + 1 //go next

          //in case if element was removed already (not found), we still provide closest node info
          if (pred.lt( Node_a.a ) == true && curr.lt( Node_a.a) == false) {
            removeFrom(0) = pred //pred less then "a" but curr already more then "a", so save the border line pred
          }
          removeFromRange_t(start, curr, to, Node_a, count, factor, merge, remove, removeFrom, false, false, newSplit)
        }
      }

    } else true
  }

}
