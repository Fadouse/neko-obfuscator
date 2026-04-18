import jdk.vm.ci.hotspot.*;
public class PrintJVMCIOffsets {
  public static void main(String[] args) {
    HotSpotJVMCIRuntime rt = HotSpotJVMCIRuntime.runtime();
    HotSpotVMConfigAccess cfg = new HotSpotVMConfigAccess(rt.getConfigStore());
    String[] fields = {
      "Method::_i2i_entry",
      "Method::_from_compiled_entry",
      "Method::_from_interpreted_entry",
      "Method::_code",
      "Method::_adapter",
      "InstanceKlass::_methods",
      "InstanceKlass::_constants",
      "Klass::_layout_helper"
    };
    for (String f : fields) {
      try {
        Object off = cfg.getFieldOffset(f, Long.class, "" );
        System.out.println(f + " = " + off);
      } catch (Throwable t) {
        try {
          Object off = cfg.getFieldOffset(f, Integer.class, "" );
          System.out.println(f + " = " + off);
        } catch (Throwable t2) {
          System.out.println(f + " -> ERROR " + t2);
        }
      }
    }
  }
}
