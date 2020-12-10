import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool.ResPool
import com.unboundid.ldap.sdk.LDAPConnection



package object zhttp 
{

    type MyEnv = MyLogging with ResPool[LDAPConnection]

}