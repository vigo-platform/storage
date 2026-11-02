package ir.vigoapp.storage;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileValidatorTest {
    @TempDir Path root;
    FileSystemBlobStore store;FileValidator validator=new FileValidator();
    @BeforeEach void setup() throws Exception{store=new FileSystemBlobStore(root.toString());}
    FileValidator.Validated validate(String name,byte[] bytes)throws Exception{return validator.validate(store.put(new ByteArrayInputStream(bytes)),name);}
    @Test void textIsStrictUtf8AndRejectsNul()throws Exception{
        assertThat(validate("plan.txt","برنامه\nexercise".getBytes(StandardCharsets.UTF_8)).mime()).startsWith("text/plain");
        assertThatThrownBy(()->validate("bad.txt",new byte[]{(byte)0xc3,0x28})).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->validate("bad.txt",new byte[]{65,0,66})).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->validate("renamed.txt","%PDF-1.7\n%%EOF".getBytes())).isInstanceOf(Api.Fault.class);
    }
    @ParameterizedTest @ValueSource(strings={"png","jpg","jpeg"})
    void imagesNeedCompleteMatchingContent(String ext)throws Exception{
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),ext.equals("png")?"png":"jpg",bytes);
        assertThat(validate("program."+ext,bytes.toByteArray()).mime()).startsWith("image/");
        assertThatThrownBy(()->validate("truncated."+ext,Arrays.copyOf(bytes.toByteArray(),bytes.size()-5))).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->validate("fake."+ext,"plain text".getBytes())).isInstanceOf(Api.Fault.class);
    }
    @Test void pdfNeedsParseableDocumentAndEndMarker()throws Exception{
        byte[] bytes;try(var doc=new PDDocument();var output=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.save(output);bytes=output.toByteArray();}
        assertThat(validate("plan.pdf",bytes).mime()).isEqualTo("application/pdf");
        assertThatThrownBy(()->validate("bad.pdf","%PDF-1.7\n%%EOF".getBytes())).isInstanceOf(Api.Fault.class);
        assertThatThrownBy(()->validate("bad.pdf",Arrays.copyOf(bytes,bytes.length-10))).isInstanceOf(Api.Fault.class);
    }
    @ParameterizedTest @ValueSource(strings={"docx","xlsx"})
    void officeRequiresCorrectPackageAndBoundedZip(String ext)throws Exception{
        var entries=office(ext);
        assertThat(validate("plan."+ext,zip(entries)).mime()).contains(ext.equals("docx")?"wordprocessingml":"spreadsheetml");
        assertThatThrownBy(()->validate("wrong."+(ext.equals("docx")?"xlsx":"docx"),zip(entries))).isInstanceOf(Api.Fault.class);
        byte[] valid=zip(entries);
        assertThatThrownBy(()->validate("truncated."+ext,Arrays.copyOf(valid,valid.length-3))).isInstanceOf(Api.Fault.class);
        byte[] trailing=Arrays.copyOf(valid,valid.length+4);
        assertThatThrownBy(()->validate("trailing."+ext,trailing)).isInstanceOf(Api.Fault.class);
        entries.put("../escape.xml","<x/>".getBytes());assertThatThrownBy(()->validate("bad."+ext,zip(entries))).isInstanceOf(Api.Fault.class);
    }
    @Test void officeRejectsEntityExpansionAndMacro()throws Exception{
        var entries=office("docx");entries.put("custom.xml","<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>".getBytes());
        byte[] entity=zip(entries);assertThatThrownBy(()->validate("bad.docx",entity)).isInstanceOf(Api.Fault.class);
        entries=office("docx");entries.put("word/vbaProject.bin",new byte[]{1});byte[] macro=zip(entries);
        assertThatThrownBy(()->validate("bad.docx",macro)).isInstanceOf(Api.Fault.class);
    }
    @Test void zipEntryCountIsBounded()throws Exception{
        var entries=office("docx");for(int i=0;i<2001;i++)entries.put("entry"+i,new byte[0]);
        assertThatThrownBy(()->validate("bomb.docx",zip(entries))).isInstanceOf(Api.Fault.class);
    }
    @Test void unsupportedExtensionIs415AndFilenameIsSanitized()throws Exception{
        assertThatThrownBy(()->validate("run.exe",new byte[]{1})).isInstanceOfSatisfying(Api.Fault.class,e->assertThat(e.status).isEqualTo(415));
        assertThat(validate("../../evil\r\n\".txt","safe".getBytes()).name()).isEqualTo("evil___.txt");
    }
    static Map<String,byte[]> office(String ext){
        var entries=new LinkedHashMap<String,byte[]>();String main=ext.equals("docx")?"word/document.xml":"xl/workbook.xml";
        String content=ext.equals("docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
        entries.put("[Content_Types].xml",("<Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Override PartName='/"+main+"' ContentType='"+content+"'/></Types>").getBytes());
        entries.put("_rels/.rels","<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'/>".getBytes());
        entries.put(main,(ext.equals("docx")?"<w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body/></w:document>":"<workbook xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'><sheets/></workbook>").getBytes());
        if(ext.equals("xlsx"))entries.put("xl/worksheets/sheet1.xml","<worksheet xmlns='http://schemas.openxmlformats.org/spreadsheetml/2006/main'/>".getBytes());return entries;
    }
    static byte[] zip(Map<String,byte[]> entries)throws Exception{
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
}
