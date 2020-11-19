package zhttp

import scala.collection.immutable.HashMap
import scala.collection.mutable.StringBuilder

object Headers {

    def apply( args : ( String, String )* ) = { 
       args.foldLeft( new Headers() ) ( (hdr, pair) => hdr + pair )
    }

    def apply( pair : ( String, String ) ) = {
        new Headers( pair )
    }    

    def apply() = new Headers()


    //small trick, which let's to use no case hashmap, and look cool: set-cookie vs Set-Cookie
    def toCamelCase( key : String) : String = {

       val T = new StringBuilder( key )

       val length = T.length
       var ii = 0;
       var flag = true;

       while( ii < length )
       {
           if( flag ) { T.setCharAt( ii, T.charAt(ii).toUpper ); flag = false }
           if ( T.charAt( ii ) == '-' ) flag = true;
          
           ii = ii + 1
       }  
          
       T.toString()
    }

}

class Headers( tbl0 : HashMap[String, Set[String]] )
{
    private val tbl : HashMap[String, Set[String]] = tbl0

    def this( pair : ( String, String ) ) = this( HashMap[String, Set[String]]( (pair._1.toLowerCase(), Set( pair._2 )) )  ) 

    def this() = this( HashMap[String, Set[String]]())


    def +( pair : ( String, String ) ) = updated( pair )

    def ++( hdrs : Headers ) : Headers = 
        new Headers ( hdrs.tbl.foldLeft( tbl )( ( tbl0, pair) => { 
                                                   val key = pair._1.toLowerCase()
                                                   val mval_set = tbl0.get( key ).getOrElse( Set() )
                                                   tbl0.updated( key, mval_set ++ pair._2  )
                                                   } ) )

    def updated( pair : ( String, String ) ) = {
        val key = pair._1.toLowerCase
        val mval_set = tbl.get( key ).getOrElse( Set() )

        val tbl_u = tbl.updated( key, mval_set + pair._2  ) 

        new Headers( tbl_u )
    }

    def get( key : String ) : Option[String] = tbl.get( key.toLowerCase ).map( set => set.head )

    def getMval( key : String ) : Set[String] = tbl.get( key.toLowerCase ).getOrElse( Set[String]() )

    def foreach( op : ( String, String ) => Unit ) : Unit =  
        tbl.foreach( kv => kv._2.foreach( a_val => op( kv._1, a_val ) ) )   

    def printHeaders : String = {
         val ZT = new StringBuilder()
         
         val lines = tbl.foldLeft( ZT ) {
            (  ( lines, kv) => kv._2.foldLeft( lines )( ( lz, val2 ) => lz.append( Headers.toCamelCase( kv._1 ) + ": " + val2.toString + "\n") ) )  
         }   
         lines.toString()
    }
}
