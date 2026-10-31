package com.wm.app.b2b.server;
import com.wm.data.*;
public class Service {
 public static User user=new User("Administrator");public static int legacyCalls;public static boolean failLegacy;
 public static java.util.Map<String,String> legacy=new java.util.HashMap<>();
 public static User getUser(){return user;}
 public static IData doInvoke(String folder,String service,IData p)throws Exception{
  if(folder.equals("wm.server.ui"))return p;
  legacyCalls++;if(failLegacy)throw new Exception("Must never expose this password: secret-from-legacy-error");
  IDataCursor c=p.getCursor();String pkg=IDataUtil.getString(c,"wxConfigPkgName"),key=IDataUtil.getString(c,"key");
  if(!"true".equals(IDataUtil.getString(c,"showEncryptedValues")))throw new Exception("Missing plaintext flag");
  IDataUtil.put(c,"propertyValue",legacy.get(pkg+"/"+key));c.destroy();return p;
 }
}
