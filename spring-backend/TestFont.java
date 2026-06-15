import com.lowagie.text.pdf.BaseFont;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestFont {
    public static void main(String[] args) throws Exception {
        byte[] regularBytes = Files.readAllBytes(Path.of("D:/PROGRAMMING/VDT/project/Medical_Chatbot/spring-backend/src/main/resources/fonts/Roboto-Regular.ttf"));
        System.out.println("Read " + regularBytes.length + " bytes");
        try {
            BaseFont regularBase = BaseFont.createFont("Roboto-Regular.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, regularBytes, null);
            System.out.println("BaseFont created successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
