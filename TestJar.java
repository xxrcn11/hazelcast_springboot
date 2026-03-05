import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.lang.reflect.Method;

public class TestJar {
    public static void main(String[] args) throws Exception {
        URL jarUrl = new URL("file:build/libs/hz-springboot-0.0.1-SNAPSHOT-plain.jar");
        URLClassLoader loader = new URLClassLoader(new URL[] { jarUrl });
        Class<?> clazz = loader.loadClass("com.bt.hz.config.MappingSQLs");
        Method m = clazz.getMethod("getAllMappings");
        List<String> mappings = (List<String>) m.invoke(null);
        System.out.println("Found " + mappings.size() + " mappings:");
        for (String s : mappings) {
            System.out.println(s);
        }
    }
}
