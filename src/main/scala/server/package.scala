import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool.ResPool
import zhttp.clients.ResPoolGroup.ResPoolGroup
import com.unboundid.ldap.sdk.LDAPConnection



package object zhttp 
{

    type MyEnv = MyLogging with ResPoolGroup[LDAPConnection]

}