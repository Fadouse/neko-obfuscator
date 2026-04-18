import jdk.vm.ci.hotspot.*;
import java.lang.reflect.*;
import java.util.*;
public class ListJVMCIFields {
  public static void main(String[] args) throws Exception {
    HotSpotJVMCIRuntime rt = HotSpotJVMCIRuntime.runtime();
    HotSpotVMConfigStore store = rt.getConfigStore();
    for (Map.Entry<String, VMField> e : store.getFields().entrySet()) {
      String n = e.getKey();
      if (n.startsWith("Method::") || n.startsWith("InstanceKlass::") || n.startsWith("Klass::")) {
        VMField f = e.getValue();
        Field off = VMField.class.getDeclaredField("offset");
        Field addr = VMField.class.getDeclaredField("address");
        Field type = VMField.class.getDeclaredField("type");
        off.setAccessible(true); addr.setAccessible(true); type.setAccessible(true);
        System.out.println(n + " type=" + type.get(f) + " offset=" + off.getLong(f) + " address=" + addr.getLong(f));
      }
    }
  }
}
