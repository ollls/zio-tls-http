package zhttp.clients

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.util.ssl.SSLUtil
import com.unboundid.util.ssl.TrustAllTrustManager

import com.unboundid.ldap.sdk.AsyncSearchResultListener
import com.unboundid.ldap.sdk.AsyncRequestID
import com.unboundid.ldap.sdk.SearchResult
import com.unboundid.ldap.sdk.SearchRequest
import com.unboundid.ldap.sdk.SearchResultEntry
import com.unboundid.ldap.sdk.SearchResultReference
import com.unboundid.ldap.sdk.SearchScope

import zio.Chunk
import zio.IO

object AsyncLDAP {

  var HOST    = "localhost"
  var PORT    = 636
  var BIND_DN = "cn=directory manager"
  var PWD     = "password"

  def ldap_con_ssl() = {
    val sslUtil          = new SSLUtil(new TrustAllTrustManager());
    val sslSocketFactory = sslUtil.createSSLSocketFactory();
    val lc               = new LDAPConnection(sslSocketFactory);

    lc.connect(HOST, PORT)
    lc.bind(BIND_DN, PWD)

    lc
  }

  def ldap_con_close(c: LDAPConnection) = { /*println("ldap con close");*/ c.close() }

  def a_search(c: LDAPConnection, baseDN: String, filter: String) =
    IO.effectAsync[Exception, Chunk[SearchResultEntry]](cb => {

      val listener = new AsyncSearchResultListener {
        var results = Chunk[SearchResultEntry]()
        /////////////////////////
        def searchResultReceived(reqId: AsyncRequestID, searchRes: SearchResult) =
          cb(IO.effectTotal(results))
        /////////////////////////
        def searchEntryReturned(searchEntry: SearchResultEntry) =
          results = results ++ Chunk(searchEntry)
        ////////////////////////
        def searchReferenceReturned(searchReference: SearchResultReference) = {}
      }

      c.asyncSearch(new SearchRequest(listener, baseDN, SearchScope.SUB, filter, "uid", "cn"))

    })

}
