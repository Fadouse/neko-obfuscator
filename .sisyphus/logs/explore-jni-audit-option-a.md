# JNI Usage Audit — Option A Strict No-JNI Compliance

## Summary
- Total JNI call sites: 14
- Files affected: 13
- Functions affected: 387
- Direct raw `(*env)->` / `(*vm)->` sites: 14
- Top hotspots: `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave1RuntimeEmitter.java` (351); `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave2FieldLdcEmitter.java` (105); `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java` (68)

## Category 1: Class lookup / resolution
| File:Line | Call | Context (1-line paraphrase) | Replacement strategy hint |
|---|---|---|---|
| `JniOnLoadEmitter.java:26` | `(*env)->FindClass` | jclass systemClass = (*env)->FindClass(env, "java/lang/System"); | Class resolution via VMStructs / ClassLoaderData / mirror walk |
| `JniOnLoadEmitter.java:28` | `(*env)->GetStaticMethodID` | jmethodID getProperty = (*env)->GetStaticMethodID(env, systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;"); | Needs per-site replacement design |

## Category 5: Other
| File:Line | Call | Context (1-line paraphrase) | Replacement strategy hint |
|---|---|---|---|
| `BootstrapEmitter.java:1834` | `(*env)->FromReflectedMethod` | reflected_mid = (*env)->FromReflectedMethod(env, reflected); | Needs per-site replacement design |
| `JniOnLoadEmitter.java:19` | `(*vm)->GetEnv` | env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6); | HotSpot-internal VM/Thread attach path or bootstrap redesign |
## Category 8: Neko-internal helpers that wrap JNI
(helper definition site plus call-sites for refactor leverage)
| Helper | Definition site(s) | Call-site count | Representative call-sites |
|---|---|---:|---|
| `neko_alloc_object` | `Wave1RuntimeEmitter.java:254` | 2 | `Wave1RuntimeEmitter.java:254`, `OpcodeTranslator.java:214` |
| `neko_box_boolean` | `Wave1RuntimeEmitter.java:398` | 3 | `Wave1RuntimeEmitter.java:398`, `OpcodeTranslator.java:1151`, `OpcodeTranslator.java:1224` |
| `neko_box_byte` | `Wave1RuntimeEmitter.java:406` | 3 | `Wave1RuntimeEmitter.java:406`, `OpcodeTranslator.java:1154`, `OpcodeTranslator.java:1225` |
| `neko_box_char` | `Wave1RuntimeEmitter.java:414` | 3 | `Wave1RuntimeEmitter.java:414`, `OpcodeTranslator.java:1157`, `OpcodeTranslator.java:1226` |
| `neko_box_double` | `Wave1RuntimeEmitter.java:454` | 3 | `Wave1RuntimeEmitter.java:454`, `OpcodeTranslator.java:1172`, `OpcodeTranslator.java:1231` |
| `neko_box_float` | `Wave1RuntimeEmitter.java:446` | 3 | `Wave1RuntimeEmitter.java:446`, `OpcodeTranslator.java:1169`, `OpcodeTranslator.java:1229` |
| `neko_box_int` | `Wave1RuntimeEmitter.java:430` | 3 | `Wave1RuntimeEmitter.java:430`, `OpcodeTranslator.java:1163`, `OpcodeTranslator.java:1228` |
| `neko_box_long` | `Wave1RuntimeEmitter.java:438` | 3 | `Wave1RuntimeEmitter.java:438`, `OpcodeTranslator.java:1166`, `OpcodeTranslator.java:1230` |
| `neko_box_short` | `Wave1RuntimeEmitter.java:422` | 3 | `Wave1RuntimeEmitter.java:422`, `OpcodeTranslator.java:1160`, `OpcodeTranslator.java:1227` |
| `neko_call_boolean_method_a` | `Wave1RuntimeEmitter.java:257` | 3 | `Wave1RuntimeEmitter.java:257`, `Wave1RuntimeEmitter.java:467`, `Wave1RuntimeEmitter.java:920` |
| `neko_call_byte_method_a` | `Wave1RuntimeEmitter.java:258` | 3 | `Wave1RuntimeEmitter.java:258`, `Wave1RuntimeEmitter.java:474`, `Wave1RuntimeEmitter.java:921` |
| `neko_call_char_method_a` | `Wave1RuntimeEmitter.java:259` | 3 | `Wave1RuntimeEmitter.java:259`, `Wave1RuntimeEmitter.java:481`, `Wave1RuntimeEmitter.java:922` |
| `neko_call_double_method_a` | `Wave1RuntimeEmitter.java:264` | 3 | `Wave1RuntimeEmitter.java:264`, `Wave1RuntimeEmitter.java:516`, `Wave1RuntimeEmitter.java:927` |
| `neko_call_float_method_a` | `Wave1RuntimeEmitter.java:263` | 3 | `Wave1RuntimeEmitter.java:263`, `Wave1RuntimeEmitter.java:509`, `Wave1RuntimeEmitter.java:926` |
| `neko_call_int_method_a` | `Wave1RuntimeEmitter.java:261` | 6 | `Wave1RuntimeEmitter.java:261`, `Wave1RuntimeEmitter.java:495`, `Wave1RuntimeEmitter.java:924`, `Wave2FieldLdcEmitter.java:95`, `Wave2FieldLdcEmitter.java:125`, `Wave2FieldLdcEmitter.java:125` |
| `neko_call_long_method_a` | `Wave1RuntimeEmitter.java:262` | 4 | `Wave1RuntimeEmitter.java:262`, `Wave1RuntimeEmitter.java:502`, `Wave1RuntimeEmitter.java:925`, `Wave2FieldLdcEmitter.java:76` |
| `neko_call_mh` | `Wave1RuntimeEmitter.java:701` | 3 | `Wave1RuntimeEmitter.java:701`, `OpcodeTranslator.java:418`, `OpcodeTranslator.java:1107` |
| `neko_call_nonvirtual_boolean_method_a` | `Wave1RuntimeEmitter.java:267` | 2 | `Wave1RuntimeEmitter.java:267`, `Wave1RuntimeEmitter.java:937` |
| `neko_call_nonvirtual_byte_method_a` | `Wave1RuntimeEmitter.java:268` | 2 | `Wave1RuntimeEmitter.java:268`, `Wave1RuntimeEmitter.java:938` |
| `neko_call_nonvirtual_char_method_a` | `Wave1RuntimeEmitter.java:269` | 2 | `Wave1RuntimeEmitter.java:269`, `Wave1RuntimeEmitter.java:939` |
| `neko_call_nonvirtual_double_method_a` | `Wave1RuntimeEmitter.java:274` | 2 | `Wave1RuntimeEmitter.java:274`, `Wave1RuntimeEmitter.java:944` |
| `neko_call_nonvirtual_float_method_a` | `Wave1RuntimeEmitter.java:273` | 2 | `Wave1RuntimeEmitter.java:273`, `Wave1RuntimeEmitter.java:943` |
| `neko_call_nonvirtual_int_method_a` | `Wave1RuntimeEmitter.java:271` | 2 | `Wave1RuntimeEmitter.java:271`, `Wave1RuntimeEmitter.java:941` |
| `neko_call_nonvirtual_long_method_a` | `Wave1RuntimeEmitter.java:272` | 2 | `Wave1RuntimeEmitter.java:272`, `Wave1RuntimeEmitter.java:942` |
| `neko_call_nonvirtual_object_method_a` | `Wave1RuntimeEmitter.java:266` | 2 | `Wave1RuntimeEmitter.java:266`, `Wave1RuntimeEmitter.java:945` |
| `neko_call_nonvirtual_short_method_a` | `Wave1RuntimeEmitter.java:270` | 2 | `Wave1RuntimeEmitter.java:270`, `Wave1RuntimeEmitter.java:940` |
| `neko_call_nonvirtual_void_method_a` | `Wave1RuntimeEmitter.java:275` | 2 | `Wave1RuntimeEmitter.java:275`, `Wave1RuntimeEmitter.java:936` |
| `neko_call_object_method_a` | `Wave1RuntimeEmitter.java:256` | 25 | `BootstrapEmitter.java:1805`, `Wave1RuntimeEmitter.java:256`, `Wave1RuntimeEmitter.java:611`, `Wave1RuntimeEmitter.java:622`, `Wave1RuntimeEmitter.java:635`, `Wave1RuntimeEmitter.java:649` |
| `neko_call_short_method_a` | `Wave1RuntimeEmitter.java:260` | 3 | `Wave1RuntimeEmitter.java:260`, `Wave1RuntimeEmitter.java:488`, `Wave1RuntimeEmitter.java:923` |
| `neko_call_static_boolean_method_a` | `Wave1RuntimeEmitter.java:295` | 2 | `Wave1RuntimeEmitter.java:295`, `Wave2FieldLdcEmitter.java:96` |
| `neko_call_static_byte_method_a` | `Wave1RuntimeEmitter.java:296` | 1 | `Wave1RuntimeEmitter.java:296` |
| `neko_call_static_char_method_a` | `Wave1RuntimeEmitter.java:297` | 1 | `Wave1RuntimeEmitter.java:297` |
| `neko_call_static_double_method_a` | `Wave1RuntimeEmitter.java:302` | 1 | `Wave1RuntimeEmitter.java:302` |
| `neko_call_static_float_method_a` | `Wave1RuntimeEmitter.java:301` | 1 | `Wave1RuntimeEmitter.java:301` |
| `neko_call_static_int_method_a` | `Wave1RuntimeEmitter.java:299` | 1 | `Wave1RuntimeEmitter.java:299` |
| `neko_call_static_long_method_a` | `Wave1RuntimeEmitter.java:300` | 1 | `Wave1RuntimeEmitter.java:300` |
| `neko_call_static_object_method_a` | `Wave1RuntimeEmitter.java:294` | 17 | `BootstrapEmitter.java:787`, `Wave1RuntimeEmitter.java:294`, `Wave1RuntimeEmitter.java:370`, `Wave1RuntimeEmitter.java:391`, `Wave1RuntimeEmitter.java:404`, `Wave1RuntimeEmitter.java:412` |
| `neko_call_static_short_method_a` | `Wave1RuntimeEmitter.java:298` | 1 | `Wave1RuntimeEmitter.java:298` |
| `neko_call_static_void_method_a` | `Wave1RuntimeEmitter.java:303` | 1 | `Wave1RuntimeEmitter.java:303` |
| `neko_call_void_method_a` | `Wave1RuntimeEmitter.java:265` | 3 | `Wave1RuntimeEmitter.java:265`, `Wave1RuntimeEmitter.java:628`, `Wave1RuntimeEmitter.java:919` |
| `neko_class_for_descriptor` | `Wave1RuntimeEmitter.java:519` | 12 | `Wave1RuntimeEmitter.java:519`, `Wave1RuntimeEmitter.java:648`, `Wave1RuntimeEmitter.java:653`, `Wave1RuntimeEmitter.java:658`, `Wave1RuntimeEmitter.java:663`, `Wave1RuntimeEmitter.java:781` |
| `neko_class_klass_pointer` | `BootstrapEmitter.java:243`, `Wave2FieldLdcEmitter.java:652` | 6 | `BootstrapEmitter.java:243`, `BootstrapEmitter.java:1276`, `BootstrapEmitter.java:1747`, `Wave2FieldLdcEmitter.java:652`, `Wave2FieldLdcEmitter.java:695`, `Wave2FieldLdcEmitter.java:723` |
| `neko_delete_global_ref` | `Wave1RuntimeEmitter.java:248` | 2 | `Wave1RuntimeEmitter.java:248`, `Wave1RuntimeEmitter.java:952` |
| `neko_delete_local_ref` | `Wave1RuntimeEmitter.java:250` | 52 | `BootstrapEmitter.java:798`, `BootstrapEmitter.java:801`, `BootstrapEmitter.java:804`, `BootstrapEmitter.java:830`, `BootstrapEmitter.java:1601`, `BootstrapEmitter.java:1607` |
| `neko_delete_weak_global_ref` | `Wave1RuntimeEmitter.java:253` | 1 | `Wave1RuntimeEmitter.java:253` |
| `neko_ensure_local_capacity` | `Wave1RuntimeEmitter.java:247` | 2 | `ImplBodyEmitter.java:33`, `Wave1RuntimeEmitter.java:247` |
| `neko_exception_check` | `Wave1RuntimeEmitter.java:356` | 71 | `BootstrapEmitter.java:789`, `BootstrapEmitter.java:806`, `BootstrapEmitter.java:818`, `BootstrapEmitter.java:827`, `BootstrapEmitter.java:1591`, `BootstrapEmitter.java:1592` |
| `neko_exception_clear` | `Wave1RuntimeEmitter.java:246` | 30 | `BootstrapEmitter.java:807`, `BootstrapEmitter.java:819`, `BootstrapEmitter.java:828`, `BootstrapEmitter.java:1593`, `BootstrapEmitter.java:1819`, `BootstrapEmitter.java:1921` |
| `neko_exception_occurred` | `Wave1RuntimeEmitter.java:245` | 4 | `Wave1RuntimeEmitter.java:38`, `Wave1RuntimeEmitter.java:245`, `NativeTranslator.java:383`, `NativeTranslator.java:386` |
| `neko_find_class` | `Wave1RuntimeEmitter.java:235` | 40 | `BootstrapEmitter.java:778`, `BootstrapEmitter.java:816`, `Wave1RuntimeEmitter.java:56`, `Wave1RuntimeEmitter.java:235`, `Wave1RuntimeEmitter.java:368`, `Wave1RuntimeEmitter.java:385` |
| `neko_get_array_length` | `Wave1RuntimeEmitter.java:326` | 8 | `BootstrapEmitter.java:1825`, `Wave1RuntimeEmitter.java:326`, `Wave1RuntimeEmitter.java:754`, `Wave1RuntimeEmitter.java:760`, `Wave1RuntimeEmitter.java:776`, `Wave1RuntimeEmitter.java:782` |
| `neko_get_boolean_array_region` | `Wave1RuntimeEmitter.java:338` | 1 | `Wave1RuntimeEmitter.java:338` |
| `neko_get_boolean_field` | `Wave1RuntimeEmitter.java:277` | 1 | `Wave1RuntimeEmitter.java:277` |
| `neko_get_byte_array_region` | `Wave1RuntimeEmitter.java:339` | 1 | `Wave1RuntimeEmitter.java:339` |
| `neko_get_byte_field` | `Wave1RuntimeEmitter.java:278` | 1 | `Wave1RuntimeEmitter.java:278` |
| `neko_get_char_array_region` | `Wave1RuntimeEmitter.java:340` | 1 | `Wave1RuntimeEmitter.java:340` |
| `neko_get_char_field` | `Wave1RuntimeEmitter.java:279` | 1 | `Wave1RuntimeEmitter.java:279` |
| `neko_get_double_array_region` | `Wave1RuntimeEmitter.java:345` | 1 | `Wave1RuntimeEmitter.java:345` |
| `neko_get_double_field` | `Wave1RuntimeEmitter.java:284` | 1 | `Wave1RuntimeEmitter.java:284` |
| `neko_get_field_id` | `Wave1RuntimeEmitter.java:240` | 4 | `Wave1RuntimeEmitter.java:240`, `Wave2FieldLdcEmitter.java:179`, `Wave3InvokeStaticEmitter.java:69`, `Wave3InvokeStaticEmitter.java:150` |
| `neko_get_float_array_region` | `Wave1RuntimeEmitter.java:344` | 1 | `Wave1RuntimeEmitter.java:344` |
| `neko_get_float_field` | `Wave1RuntimeEmitter.java:283` | 1 | `Wave1RuntimeEmitter.java:283` |
| `neko_get_indy_mh` | `Wave1RuntimeEmitter.java:554` | 3 | `Wave1RuntimeEmitter.java:554`, `Wave1RuntimeEmitter.java:750`, `OpcodeTranslator.java:1089` |
| `neko_get_int_array_region` | `Wave1RuntimeEmitter.java:342` | 1 | `Wave1RuntimeEmitter.java:342` |
| `neko_get_int_field` | `Wave1RuntimeEmitter.java:281` | 1 | `Wave1RuntimeEmitter.java:281` |
| `neko_get_long_array_region` | `Wave1RuntimeEmitter.java:343` | 1 | `Wave1RuntimeEmitter.java:343` |
| `neko_get_long_field` | `Wave1RuntimeEmitter.java:282` | 1 | `Wave1RuntimeEmitter.java:282` |
| `neko_get_method_id` | `Wave1RuntimeEmitter.java:238` | 21 | `Wave1RuntimeEmitter.java:58`, `Wave1RuntimeEmitter.java:238`, `Wave1RuntimeEmitter.java:610`, `Wave1RuntimeEmitter.java:618`, `Wave1RuntimeEmitter.java:625`, `Wave1RuntimeEmitter.java:631` |
| `neko_get_object_array_element` | `Wave1RuntimeEmitter.java:328` | 5 | `BootstrapEmitter.java:1827`, `Wave1RuntimeEmitter.java:328`, `Wave1RuntimeEmitter.java:761`, `Wave1RuntimeEmitter.java:783`, `OpcodeTranslator.java:109` |
| `neko_get_object_class` | `Wave1RuntimeEmitter.java:236` | 3 | `Wave1RuntimeEmitter.java:236`, `Wave1RuntimeEmitter.java:1006`, `OpcodeTranslator.java:367` |
| `neko_get_object_field` | `Wave1RuntimeEmitter.java:276` | 1 | `Wave1RuntimeEmitter.java:276` |
| `neko_get_short_array_region` | `Wave1RuntimeEmitter.java:341` | 1 | `Wave1RuntimeEmitter.java:341` |
| `neko_get_short_field` | `Wave1RuntimeEmitter.java:280` | 1 | `Wave1RuntimeEmitter.java:280` |
| `neko_get_static_boolean_field` | `Wave1RuntimeEmitter.java:305` | 1 | `Wave1RuntimeEmitter.java:305` |
| `neko_get_static_byte_field` | `Wave1RuntimeEmitter.java:306` | 1 | `Wave1RuntimeEmitter.java:306` |
| `neko_get_static_char_field` | `Wave1RuntimeEmitter.java:307` | 1 | `Wave1RuntimeEmitter.java:307` |
| `neko_get_static_double_field` | `Wave1RuntimeEmitter.java:312` | 1 | `Wave1RuntimeEmitter.java:312` |
| `neko_get_static_field_id` | `Wave1RuntimeEmitter.java:241` | 15 | `BootstrapEmitter.java:823`, `Wave1RuntimeEmitter.java:241`, `Wave1RuntimeEmitter.java:521`, `Wave1RuntimeEmitter.java:522`, `Wave1RuntimeEmitter.java:523`, `Wave1RuntimeEmitter.java:524` |
| `neko_get_static_float_field` | `Wave1RuntimeEmitter.java:311` | 1 | `Wave1RuntimeEmitter.java:311` |
| `neko_get_static_int_field` | `Wave1RuntimeEmitter.java:309` | 1 | `Wave1RuntimeEmitter.java:309` |
| `neko_get_static_long_field` | `Wave1RuntimeEmitter.java:310` | 1 | `Wave1RuntimeEmitter.java:310` |
| `neko_get_static_method_id` | `Wave1RuntimeEmitter.java:239` | 9 | `BootstrapEmitter.java:780`, `Wave1RuntimeEmitter.java:239`, `Wave1RuntimeEmitter.java:369`, `Wave1RuntimeEmitter.java:386`, `Wave1RuntimeEmitter.java:579`, `Wave1RuntimeEmitter.java:591` |
| `neko_get_static_object_field` | `Wave1RuntimeEmitter.java:304` | 11 | `Wave1RuntimeEmitter.java:304`, `Wave1RuntimeEmitter.java:521`, `Wave1RuntimeEmitter.java:522`, `Wave1RuntimeEmitter.java:523`, `Wave1RuntimeEmitter.java:524`, `Wave1RuntimeEmitter.java:525` |
| `neko_get_static_short_field` | `Wave1RuntimeEmitter.java:308` | 1 | `Wave1RuntimeEmitter.java:308` |
| `neko_get_string_length` | `Wave1RuntimeEmitter.java:322` | 2 | `Wave1RuntimeEmitter.java:322`, `OpcodeTranslator.java:362` |
| `neko_get_string_utf_chars` | `Wave1RuntimeEmitter.java:324` | 2 | `BootstrapEmitter.java:790`, `Wave1RuntimeEmitter.java:324` |
| `neko_is_instance_of` | `Wave1RuntimeEmitter.java:237` | 5 | `Wave1RuntimeEmitter.java:237`, `Wave1RuntimeEmitter.java:767`, `NativeTranslator.java:392`, `OpcodeTranslator.java:232`, `OpcodeTranslator.java:236` |
| `neko_load_class_noinit` | `Wave1RuntimeEmitter.java:367` | 3 | `BootstrapEmitter.java:1918`, `Wave1RuntimeEmitter.java:367`, `Wave2FieldLdcEmitter.java:219` |
| `neko_load_class_noinit_with_loader` | `CCodeGenerator.java:445`, `Wave1RuntimeEmitter.java:377` | 4 | `CCodeGenerator.java:445`, `Wave1RuntimeEmitter.java:371`, `Wave1RuntimeEmitter.java:377`, `Wave2FieldLdcEmitter.java:720` |
| `neko_monitor_enter` | `Wave1RuntimeEmitter.java:354` | 2 | `Wave1RuntimeEmitter.java:354`, `OpcodeTranslator.java:204` |
| `neko_monitor_exit` | `Wave1RuntimeEmitter.java:355` | 2 | `Wave1RuntimeEmitter.java:355`, `OpcodeTranslator.java:205` |
| `neko_multi_new_array` | `Wave1RuntimeEmitter.java:788` | 3 | `Wave1RuntimeEmitter.java:788`, `Wave1RuntimeEmitter.java:813`, `OpcodeTranslator.java:959` |
| `neko_new_exception_oop` | `Wave1RuntimeEmitter.java:49` | 7 | `Wave1RuntimeEmitter.java:49`, `Wave1RuntimeEmitter.java:156`, `Wave2FieldLdcEmitter.java:29`, `Wave2FieldLdcEmitter.java:34`, `Wave2FieldLdcEmitter.java:210`, `Wave2FieldLdcEmitter.java:711` |
| `neko_new_global_ref` | `Wave1RuntimeEmitter.java:249` | 15 | `Wave1RuntimeEmitter.java:249`, `Wave1RuntimeEmitter.java:562`, `Wave1RuntimeEmitter.java:1010`, `Wave1RuntimeEmitter.java:1021`, `Wave2FieldLdcEmitter.java:45`, `Wave2FieldLdcEmitter.java:55` |
| `neko_new_object_array` | `Wave1RuntimeEmitter.java:327` | 11 | `Wave1RuntimeEmitter.java:327`, `Wave1RuntimeEmitter.java:756`, `Wave1RuntimeEmitter.java:778`, `Wave1RuntimeEmitter.java:804`, `Wave1RuntimeEmitter.java:811`, `OpcodeTranslator.java:219` |
| `neko_new_string_utf` | `Wave1RuntimeEmitter.java:323` | 15 | `BootstrapEmitter.java:782`, `BootstrapEmitter.java:1590`, `Wave1RuntimeEmitter.java:61`, `Wave1RuntimeEmitter.java:323`, `Wave1RuntimeEmitter.java:387`, `Wave1RuntimeEmitter.java:602` |
| `neko_new_weak_global_ref` | `Wave1RuntimeEmitter.java:252` | 1 | `Wave1RuntimeEmitter.java:252` |
| `neko_owner_class_loader` | `Wave2FieldLdcEmitter.java:618` | 4 | `Wave2FieldLdcEmitter.java:618`, `Wave2FieldLdcEmitter.java:643`, `Wave2FieldLdcEmitter.java:645`, `Wave2FieldLdcEmitter.java:716` |
| `neko_release_string_utf_chars` | `Wave1RuntimeEmitter.java:325` | 2 | `BootstrapEmitter.java:795`, `Wave1RuntimeEmitter.java:325` |
| `neko_resolve_constant_dynamic` | `Wave1RuntimeEmitter.java:774` | 2 | `Wave1RuntimeEmitter.java:774`, `OpcodeTranslator.java:1217` |
| `neko_resolve_indy` | `Wave1RuntimeEmitter.java:749` | 2 | `Wave1RuntimeEmitter.java:749`, `OpcodeTranslator.java:1097` |
| `neko_set_boolean_array_region` | `Wave1RuntimeEmitter.java:346` | 1 | `Wave1RuntimeEmitter.java:346` |
| `neko_set_boolean_field` | `Wave1RuntimeEmitter.java:286` | 1 | `Wave1RuntimeEmitter.java:286` |
| `neko_set_byte_array_region` | `Wave1RuntimeEmitter.java:347` | 1 | `Wave1RuntimeEmitter.java:347` |
| `neko_set_byte_field` | `Wave1RuntimeEmitter.java:287` | 1 | `Wave1RuntimeEmitter.java:287` |
| `neko_set_char_array_region` | `Wave1RuntimeEmitter.java:348` | 1 | `Wave1RuntimeEmitter.java:348` |
| `neko_set_char_field` | `Wave1RuntimeEmitter.java:288` | 1 | `Wave1RuntimeEmitter.java:288` |
| `neko_set_double_array_region` | `Wave1RuntimeEmitter.java:353` | 1 | `Wave1RuntimeEmitter.java:353` |
| `neko_set_double_field` | `Wave1RuntimeEmitter.java:293` | 1 | `Wave1RuntimeEmitter.java:293` |
| `neko_set_float_array_region` | `Wave1RuntimeEmitter.java:352` | 1 | `Wave1RuntimeEmitter.java:352` |
| `neko_set_float_field` | `Wave1RuntimeEmitter.java:292` | 1 | `Wave1RuntimeEmitter.java:292` |
| `neko_set_int_array_region` | `Wave1RuntimeEmitter.java:350` | 1 | `Wave1RuntimeEmitter.java:350` |
| `neko_set_int_field` | `Wave1RuntimeEmitter.java:290` | 1 | `Wave1RuntimeEmitter.java:290` |
| `neko_set_long_array_region` | `Wave1RuntimeEmitter.java:351` | 1 | `Wave1RuntimeEmitter.java:351` |
| `neko_set_long_field` | `Wave1RuntimeEmitter.java:291` | 1 | `Wave1RuntimeEmitter.java:291` |
| `neko_set_object_array_element` | `Wave1RuntimeEmitter.java:329` | 14 | `Wave1RuntimeEmitter.java:329`, `Wave1RuntimeEmitter.java:757`, `Wave1RuntimeEmitter.java:758`, `Wave1RuntimeEmitter.java:759`, `Wave1RuntimeEmitter.java:761`, `Wave1RuntimeEmitter.java:779` |
| `neko_set_object_field` | `Wave1RuntimeEmitter.java:285` | 1 | `Wave1RuntimeEmitter.java:285` |
| `neko_set_pending_exception` | `Wave1RuntimeEmitter.java:124` | 8 | `Wave1RuntimeEmitter.java:124`, `Wave1RuntimeEmitter.java:147`, `Wave1RuntimeEmitter.java:161`, `Wave2FieldLdcEmitter.java:30`, `Wave2FieldLdcEmitter.java:36`, `Wave2FieldLdcEmitter.java:212` |
| `neko_set_short_array_region` | `Wave1RuntimeEmitter.java:349` | 1 | `Wave1RuntimeEmitter.java:349` |
| `neko_set_short_field` | `Wave1RuntimeEmitter.java:289` | 1 | `Wave1RuntimeEmitter.java:289` |
| `neko_set_static_boolean_field` | `Wave1RuntimeEmitter.java:314` | 2 | `BootstrapEmitter.java:825`, `Wave1RuntimeEmitter.java:314` |
| `neko_set_static_byte_field` | `Wave1RuntimeEmitter.java:315` | 1 | `Wave1RuntimeEmitter.java:315` |
| `neko_set_static_char_field` | `Wave1RuntimeEmitter.java:316` | 1 | `Wave1RuntimeEmitter.java:316` |
| `neko_set_static_double_field` | `Wave1RuntimeEmitter.java:321` | 1 | `Wave1RuntimeEmitter.java:321` |
| `neko_set_static_float_field` | `Wave1RuntimeEmitter.java:320` | 1 | `Wave1RuntimeEmitter.java:320` |
| `neko_set_static_int_field` | `Wave1RuntimeEmitter.java:318` | 1 | `Wave1RuntimeEmitter.java:318` |
| `neko_set_static_long_field` | `Wave1RuntimeEmitter.java:319` | 1 | `Wave1RuntimeEmitter.java:319` |
| `neko_set_static_object_field` | `Wave1RuntimeEmitter.java:313` | 1 | `Wave1RuntimeEmitter.java:313` |
| `neko_set_static_short_field` | `Wave1RuntimeEmitter.java:317` | 1 | `Wave1RuntimeEmitter.java:317` |
| `neko_string_concat2` | `Wave1RuntimeEmitter.java:714` | 3 | `Wave1RuntimeEmitter.java:714`, `NativeTranslator.java:335`, `OpcodeTranslator.java:1079` |
| `neko_string_concat_string` | `Wave1RuntimeEmitter.java:731` | 3 | `Wave1RuntimeEmitter.java:731`, `NativeTranslator.java:328`, `OpcodeTranslator.java:1073` |
| `neko_string_null` | `Wave1RuntimeEmitter.java:709` | 4 | `Wave1RuntimeEmitter.java:709`, `Wave1RuntimeEmitter.java:740`, `Wave1RuntimeEmitter.java:745`, `OpcodeTranslator.java:1078` |
| `neko_take_pending_jni_exception_oop` | `Wave1RuntimeEmitter.java:34` | 8 | `Wave1RuntimeEmitter.java:34`, `Wave1RuntimeEmitter.java:73`, `Wave1RuntimeEmitter.java:158`, `Wave2FieldLdcEmitter.java:28`, `Wave2FieldLdcEmitter.java:35`, `Wave2FieldLdcEmitter.java:211` |
| `neko_throw` | `Wave1RuntimeEmitter.java:243` | 4 | `Wave1RuntimeEmitter.java:243`, `NativeTranslator.java:383`, `NativeTranslator.java:395`, `OpcodeTranslator.java:203` |
| `neko_throw_new` | `Wave1RuntimeEmitter.java:244` | 5 | `Wave1RuntimeEmitter.java:244`, `Wave3InvokeStaticEmitter.java:84`, `OpcodeTranslator.java:236`, `OpcodeTranslator.java:362`, `OpcodeTranslator.java:367` |
| `neko_to_reflected_field` | `Wave1RuntimeEmitter.java:242` | 2 | `Wave1RuntimeEmitter.java:242`, `Wave2FieldLdcEmitter.java:181` |
| `neko_unbox_boolean` | `Wave1RuntimeEmitter.java:462` | 2 | `Wave1RuntimeEmitter.java:462`, `OpcodeTranslator.java:1239` |
| `neko_unbox_byte` | `Wave1RuntimeEmitter.java:469` | 2 | `Wave1RuntimeEmitter.java:469`, `OpcodeTranslator.java:1240` |
| `neko_unbox_char` | `Wave1RuntimeEmitter.java:476` | 2 | `Wave1RuntimeEmitter.java:476`, `OpcodeTranslator.java:1241` |
| `neko_unbox_double` | `Wave1RuntimeEmitter.java:511` | 2 | `Wave1RuntimeEmitter.java:511`, `OpcodeTranslator.java:1246` |
| `neko_unbox_float` | `Wave1RuntimeEmitter.java:504` | 2 | `Wave1RuntimeEmitter.java:504`, `OpcodeTranslator.java:1244` |
| `neko_unbox_int` | `Wave1RuntimeEmitter.java:490` | 2 | `Wave1RuntimeEmitter.java:490`, `OpcodeTranslator.java:1243` |
| `neko_unbox_long` | `Wave1RuntimeEmitter.java:497` | 2 | `Wave1RuntimeEmitter.java:497`, `OpcodeTranslator.java:1245` |
| `neko_unbox_short` | `Wave1RuntimeEmitter.java:483` | 2 | `Wave1RuntimeEmitter.java:483`, `OpcodeTranslator.java:1242` |

## Category 9: Steady-state (translated method body) JNI
| File:Line | Call/helper | Context (1-line paraphrase) | Replacement strategy hint |
|---|---|---|---|
| `OpcodeTranslator.java:105` | `neko_*` | case Opcodes.IALOAD -> stmts.add(raw("{ jint __i = POP_I(); jintArray __a = (jintArray)POP_O(); PUSH_I(neko_fast_iaload(env, __a, __i)); /*  | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:106` | `neko_*` | case Opcodes.LALOAD -> stmts.add(raw("{ jint __i = POP_I(); jlongArray __a = (jlongArray)POP_O(); PUSH_L(neko_fast_laload(env, __a, __i)); / | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:107` | `neko_*` | case Opcodes.FALOAD -> stmts.add(raw("{ jint __i = POP_I(); jfloatArray __a = (jfloatArray)POP_O(); PUSH_F(neko_fast_faload(env, __a, __i)); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:108` | `neko_*` | case Opcodes.DALOAD -> stmts.add(raw("{ jint __i = POP_I(); jdoubleArray __a = (jdoubleArray)POP_O(); PUSH_D(neko_fast_daload(env, __a, __i) | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:109` | `neko_*` | case Opcodes.AALOAD -> stmts.add(raw("{ jint __i = POP_I(); jobjectArray __a = (jobjectArray)POP_O(); PUSH_O(neko_get_object_array_element(e | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:110` | `neko_*` | case Opcodes.BALOAD -> stmts.add(raw("{ jint __i = POP_I(); jbyteArray __a = (jbyteArray)POP_O(); PUSH_I((jint)neko_fast_baload(env, __a, __ | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:111` | `neko_*` | case Opcodes.CALOAD -> stmts.add(raw("{ jint __i = POP_I(); jcharArray __a = (jcharArray)POP_O(); PUSH_I((jint)neko_fast_caload(env, __a, __ | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:112` | `neko_*` | case Opcodes.SALOAD -> stmts.add(raw("{ jint __i = POP_I(); jshortArray __a = (jshortArray)POP_O(); PUSH_I((jint)neko_fast_saload(env, __a,  | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:114` | `neko_*` | case Opcodes.IASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jintArray __a = (jintArray)POP_O(); neko_fast_iastore(env,  | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:115` | `neko_*` | case Opcodes.LASTORE -> stmts.add(raw("{ jlong __v = POP_L(); jint __i = POP_I(); jlongArray __a = (jlongArray)POP_O(); neko_fast_lastore(en | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:116` | `neko_*` | case Opcodes.FASTORE -> stmts.add(raw("{ jfloat __v = POP_F(); jint __i = POP_I(); jfloatArray __a = (jfloatArray)POP_O(); neko_fast_fastore | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:117` | `neko_*` | case Opcodes.DASTORE -> stmts.add(raw("{ jdouble __v = POP_D(); jint __i = POP_I(); jdoubleArray __a = (jdoubleArray)POP_O(); neko_fast_dast | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:118` | `neko_*` | case Opcodes.AASTORE -> stmts.add(raw("{ jobject __v = POP_O(); jint __i = POP_I(); jobjectArray __a = (jobjectArray)POP_O(); neko_set_objec | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:119` | `neko_*` | case Opcodes.BASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jbyteArray __a = (jbyteArray)POP_O(); neko_fast_bastore(env | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:120` | `neko_*` | case Opcodes.CASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jcharArray __a = (jcharArray)POP_O(); neko_fast_castore(env | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:121` | `neko_*` | case Opcodes.SASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jshortArray __a = (jshortArray)POP_O(); neko_fast_sastore(e | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:202` | `neko_*` | case Opcodes.ARRAYLENGTH -> stmts.add(raw("{ jarray arr = (jarray)POP_O(); PUSH_I(neko_get_array_length(env, arr)); }")); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:203` | `neko_*` | case Opcodes.ATHROW -> stmts.add(raw("{ neko_throw(env, (jthrowable)POP_O()); }")); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:204` | `neko_*` | case Opcodes.MONITORENTER -> stmts.add(raw("neko_monitor_enter(env, POP_O());")); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:205` | `neko_*` | case Opcodes.MONITOREXIT -> stmts.add(raw("neko_monitor_exit(env, POP_O());")); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:214` | `neko_*` | stmts.add(raw("{ jclass cls = " + cachedClassExpression(ti.desc) + "; if (cls != NULL) { PUSH_O(neko_alloc_object(env, cls)); } }")); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:219` | `neko_*` | stmts.add(raw("{ jint len = POP_I(); jclass cls = " + cachedClassExpression(ti.desc) + "; if (cls != NULL) { PUSH_O(neko_new_object_array(en | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:232` | `neko_*` | stmts.add(raw("{ jobject obj = POP_O(); jclass cls = " + cachedTypeClassExpression(ti.desc) + "; jint result = 0; if (cls != NULL) { result  | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:236` | `neko_*` | stmts.add(raw("{ jobject obj = POP_O(); if (obj != NULL) { jclass cls = " + cachedTypeClassExpression(ti.desc) + "; if (cls != NULL && !neko | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:281` | `neko_*` | stmts.add(raw("{ void *__ldc = neko_ldc_string_site_oop(env, " + siteExpr + "); if (neko_exception_check(env)) goto __neko_exception_exit; P | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:337` | `neko_*` | sb.append("neko_icache_dispatch(env, &").append(cacheSite).append(", &") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:340` | `neko_*` | sb.append("jvalue __ic_result = neko_icache_dispatch(env, &").append(cacheSite).append(", &") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:342` | `neko_*` | sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "__ic_result" + jvalueAccessor(ret))).append(" } "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:362` | `neko_*` | + "; neko_throw_new(env, exc, \"\"); } else { PUSH_I((jint)neko_get_string_length(env, obj)); } }"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:367` | `neko_*` | + "; neko_throw_new(env, exc, \"\"); } else { PUSH_O(neko_get_object_class(env, obj)); } }"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:413` | `neko_*` | sb.append("jobjectArray __invokeArgs = neko_new_object_array(env, ").append(args.length).append(", __objCls, NULL); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:415` | `neko_*` | sb.append("neko_set_object_array_element(env, __invokeArgs, ").append(i).append(", ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:418` | `neko_*` | sb.append("jobject __invokeResult = neko_call_mh(env, __mh, __invokeArgs); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:470` | `neko_*` | sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "result")).append(" } "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:652` | `neko_*` | return jniTypeName(ret) + " result = (" + jniTypeName(ret) + ")0; if (" + guardExpr + ") { result = " + wrapper + "(" + callArgs + "); } if  | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:719` | `neko_*` | sb.append("if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStat | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:740` | `neko_*` | sb.append("if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStat | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:854` | `neko_*` | case 4 -> "neko_new_boolean_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:855` | `neko_*` | case 5 -> "neko_new_char_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:856` | `neko_*` | case 6 -> "neko_new_float_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:857` | `neko_*` | case 7 -> "neko_new_double_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:858` | `neko_*` | case 8 -> "neko_new_byte_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:859` | `neko_*` | case 9 -> "neko_new_short_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:860` | `neko_*` | case 10 -> "neko_new_int_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:861` | `neko_*` | case 11 -> "neko_new_long_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:862` | `neko_*` | default -> "neko_new_int_array(env, len)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:885` | `neko_*` | return "neko_class_for_descriptor(env, \"" + cStringLiteral(typeToDescriptor(desc)) + "\")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:959` | `neko_*` | sb.append("PUSH_O(neko_multi_new_array(env, ").append(insn.dims).append(", __dims, \"") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:984` | `neko_*` | sb.append("jobject __sb = (__sbCls != NULL && __sbCtor != NULL) ? neko_new_object_a(env, __sbCls, __sbCtor, NULL) : NULL; "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1012` | `neko_*` | sb.append("if (__sb != NULL && __toString != NULL) { PUSH_O(neko_call_object_method_a(env, __sb, __toString, NULL)); } }"); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1073` | `neko_*` | sb.append("if (__acc == NULL) { __acc = ").append(literalExpr).append("; } else { __acc = neko_string_concat_string(env, __acc, ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1078` | `neko_*` | sb.append("if (__acc == NULL) { __acc = (jstring)(").append(valueExpr).append(" == NULL ? neko_string_null(env) : ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1079` | `neko_*` | .append(valueExpr).append("); } else { __acc = neko_string_concat2(env, __acc, ").append(valueExpr).append("); } "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1090` | `neko_*` | sb.append("jclass __objCls = neko_find_class(env, \"java/lang/Object\"); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1092` | `neko_*` | sb.append("jobjectArray __bootstrapArgs = neko_new_object_array(env, ").append(bootstrapArgs.length).append(", __objCls, NULL); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1097` | `neko_*` | sb.append("__mh = neko_resolve_indy(env, ").append(siteId).append("LL, \"").append(cStringLiteral(currentOwnerInternalName)).append("\", \"" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1102` | `neko_*` | sb.append("jobjectArray __invokeArgs = neko_new_object_array(env, ").append(argTypes.length).append(", __objCls, NULL); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1104` | `neko_*` | sb.append("neko_set_object_array_element(env, __invokeArgs, ").append(i).append(", ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1107` | `neko_*` | sb.append("jobject __indyResult = neko_call_mh(env, __mh, __invokeArgs); "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1142` | `neko_*` | sb.append("neko_set_object_array_element(env, ").append(arrayVar).append(", ").append(index).append(", ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1151` | `neko_*` | return "neko_box_boolean(env, " + (value ? "JNI_TRUE" : "JNI_FALSE") + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1154` | `neko_*` | return "neko_box_byte(env, (jbyte)" + value + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1157` | `neko_*` | return "neko_box_char(env, (jchar)" + (int) value.charValue() + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1160` | `neko_*` | return "neko_box_short(env, (jshort)" + value + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1163` | `neko_*` | return "neko_box_int(env, " + value + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1166` | `neko_*` | return "neko_box_long(env, " + value + "LL)"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1169` | `neko_*` | return "neko_box_float(env, " + floatLiteral(value) + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1172` | `neko_*` | return "neko_box_double(env, " + doubleLiteral(value) + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1179` | `neko_*` | ? "neko_method_type_from_descriptor(env, \"" + cStringLiteral(type.getDescriptor()) + "\")" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1180` | `neko_*` | : "neko_class_for_descriptor(env, \"" + cStringLiteral(type.getDescriptor()) + "\")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1183` | `neko_*` | return "neko_method_handle_from_parts(env, " + handle.getTag() + ", \"" + cStringLiteral(handle.getOwner()) + "\", \"" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1196` | `neko_*` | ? "neko_class_for_descriptor(env, \"" + cStringLiteral(componentType.getDescriptor()) + "\")" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1198` | `neko_*` | sb.append("jobjectArray ").append(arrayVar).append(" = neko_new_object_array(env, ").append(array.length).append(", ") | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1210` | `neko_*` | sb.append("jobjectArray ").append(argsVar).append(" = neko_new_object_array(env, ").append(constantDynamic.getBootstrapMethodArgumentCount() | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1217` | `neko_*` | return "neko_resolve_constant_dynamic(env, \"" + cStringLiteral(currentOwnerInternalName) + "\", \"" + cStringLiteral(constantDynamic.getNam | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1224` | `neko_*` | case Type.BOOLEAN -> "neko_box_boolean(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1225` | `neko_*` | case Type.BYTE -> "neko_box_byte(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1226` | `neko_*` | case Type.CHAR -> "neko_box_char(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1227` | `neko_*` | case Type.SHORT -> "neko_box_short(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1228` | `neko_*` | case Type.INT -> "neko_box_int(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1229` | `neko_*` | case Type.FLOAT -> "neko_box_float(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1230` | `neko_*` | case Type.LONG -> "neko_box_long(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1231` | `neko_*` | case Type.DOUBLE -> "neko_box_double(env, " + valueExpr + ")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1239` | `neko_*` | case Type.BOOLEAN -> "PUSH_I(neko_unbox_boolean(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1240` | `neko_*` | case Type.BYTE -> "PUSH_I((jint)neko_unbox_byte(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1241` | `neko_*` | case Type.CHAR -> "PUSH_I((jint)neko_unbox_char(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1242` | `neko_*` | case Type.SHORT -> "PUSH_I((jint)neko_unbox_short(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1243` | `neko_*` | case Type.INT -> "PUSH_I(neko_unbox_int(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1244` | `neko_*` | case Type.FLOAT -> "PUSH_F(neko_unbox_float(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1245` | `neko_*` | case Type.LONG -> "PUSH_L(neko_unbox_long(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1246` | `neko_*` | case Type.DOUBLE -> "PUSH_D(neko_unbox_double(env, " + objExpr + ")); "; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1255` | `neko_*` | sb.append("if (__append != NULL) { neko_call_object_method_a(env, __sb, __append, __appendArgs); } } "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1261` | `neko_*` | sb.append("if (__append != NULL) { neko_call_object_method_a(env, __sb, __append, __appendArgs); } } "); | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1266` | `neko_*` | return "neko_bound_class(env, " + classCacheVar(owner) + ", \"" + cStringLiteral(owner) + "\")"; | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1271` | `neko_*` | return "neko_bound_method(env, " + methodCacheVar(owner, name, desc, isStatic) + ", \"" + cStringLiteral(owner) + "\", \"" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1277` | `neko_*` | return "neko_bound_field(env, " + fieldCacheVar(owner, name, desc, isStatic) + ", \"" + cStringLiteral(owner) + "\", \"" | Architectural elimination in translated bodies or wrapper replacement |
| `OpcodeTranslator.java:1296` | `neko_*` | return "neko_bound_string(env, " + cacheVar + ", \"" + cStringLiteral(value) + "\")"; | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:328` | `neko_*` | + " = (jstring)neko_new_global_ref(env, neko_new_string_utf(env, \"" + c(s) + "\")); } PUSH_O(neko_string_concat_string(env, " | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:335` | `neko_*` | code = "{ PUSH_O(neko_string_concat2(env, " + firstExpr + ", " + secondExpr + ")); }"; | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:354` | `neko_*` | return "neko_new_string_utf(env, \"" + c(s) + "\")"; | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:381` | `neko_*` | sb.append("if (neko_exception_check(env)) { "); | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:383` | `neko_*` | sb.append("jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; } | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:386` | `neko_*` | sb.append("jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); "); | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:391` | `neko_*` | sb.append("{ jclass __hcls = neko_find_class(env, \"").append(c(handler.exceptionType)).append("\"); "); | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:392` | `neko_*` | sb.append("if (__hcls != NULL && neko_is_instance_of(env, __exc, __hcls)) { sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).appe | Architectural elimination in translated bodies or wrapper replacement |
| `NativeTranslator.java:395` | `neko_*` | sb.append("neko_throw(env, __exc); goto __neko_exception_exit; }"); | Architectural elimination in translated bodies or wrapper replacement |
| `ImplBodyEmitter.java:23` | `neko_*` | sb.append("    JNIEnv *env = neko_current_env();\n"); | Architectural elimination in translated bodies or wrapper replacement |
| `ImplBodyEmitter.java:33` | `neko_*` | sb.append("    if (env != NULL) neko_ensure_local_capacity(env, 8192);\n"); | Architectural elimination in translated bodies or wrapper replacement |

## Generated C code patterns (example site references)
- `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/JniOnLoadEmitter.java:19-47` emits literal `(*vm)->GetEnv`, `(*env)->FindClass`, `(*env)->GetStaticMethodID`, string helpers, local-ref cleanup, exception probes.
- `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/Wave1RuntimeEmitter.java:233-356` centralizes JNI table-slot wrappers through `NEKO_JNI_FN_PTR(env, idx, ...)`.
- `/mnt/d/Code/Security/NekoObfuscator/worktree/dev-impl/neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:109-1255` emits steady-state calls against `neko_*` wrappers for arrays, fields, invokes, indy, strings, boxing.
