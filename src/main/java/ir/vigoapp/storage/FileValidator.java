package ir.vigoapp.storage;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Component;

@Component
final class FileValidator {
    private static final Map<String,String> TYPES=Map.of("pdf","application/pdf","png","image/png","jpg","image/jpeg","jpeg","image/jpeg","docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document","xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","txt","text/plain; charset=UTF-8");
    record Validated(String name,String mime) {}
    Validated validate(BlobStore.Staged staged,String original) {
        String name=sanitize(original);
        int dot=name.lastIndexOf('.'); String ext=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        String mime=TYPES.get(ext); if(mime==null)throw Api.fail(415,"UNSUPPORTED_MEDIA_TYPE");
        try {
            byte[] prefix; try(InputStream in=Files.newInputStream(staged.path())){prefix=in.readNBytes(16);}
            switch(ext) {
                case "txt" -> {
                    String ascii=new String(prefix,StandardCharsets.ISO_8859_1);
                    if(ascii.startsWith("%PDF-")||ascii.startsWith("PK\u0003\u0004")||(prefix.length>1&&(prefix[0]&255)==255&&(prefix[1]&255)==216))throw new IOException("Binary signature in text");
                    text(staged.path());
                }
                case "png","jpg","jpeg" -> image(staged.path(),prefix,ext);
                case "pdf" -> pdf(staged.path(),prefix);
                case "docx","xlsx" -> office(staged.path(),ext);
                default -> throw new IOException("Unsupported");
            }
            return new Validated(name,mime);
        } catch(Exception e) { if(e instanceof Api.Fault f)throw f; throw Api.fail(400,"FILE_CONTENT_MISMATCH"); }
    }
    static String sanitize(String original) {
        if(original==null||original.isBlank())throw Api.fail(400,"VALIDATION_FAILED");
        String name=original.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
        name=name.replaceAll("[\\p{Cntrl}\\p{Cf}\";]","_");
        if(name.isBlank()||name.length()>255)throw Api.fail(400,"VALIDATION_FAILED"); return name;
    }
    private static void text(Path path) throws IOException {
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(Reader reader=new InputStreamReader(Files.newInputStream(path),decoder)) {
            char[] chars=new char[8192]; int n; while((n=reader.read(chars))!=-1)for(int i=0;i<n;i++)if(chars[i]==0)throw new IOException("NUL");
        }
    }
    private static void image(Path path,byte[] head,String ext) throws IOException {
        byte[] bytes=Files.readAllBytes(path);
        if(ext.equals("png")) {
            if(head.length<8||!Arrays.equals(Arrays.copyOf(head,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw new IOException("PNG signature");
            int pos=8; boolean ihdr=false,idat=false,iend=false;
            while(pos+12<=bytes.length) {
                int len=ByteBuffer.wrap(bytes,pos,4).getInt();
                if(len<0||((long)pos+12+len)>bytes.length)throw new IOException("PNG truncated");
                String type=new String(bytes,pos+4,4,StandardCharsets.US_ASCII);
                CRC32 crc=new CRC32();crc.update(bytes,pos+4,4+len);
                if((int)crc.getValue()!=ByteBuffer.wrap(bytes,pos+8+len,4).getInt())throw new IOException("PNG CRC");
                if(pos==8&&(!type.equals("IHDR")||len!=13))throw new IOException("PNG header");
                ihdr|=type.equals("IHDR");idat|=type.equals("IDAT");
                pos+=12+len;
                if(type.equals("IEND")){if(len!=0||pos!=bytes.length)throw new IOException("PNG end");iend=true;break;}
            }
            if(!ihdr||!idat||!iend)throw new IOException("PNG incomplete");
        } else if(bytes.length<4||(bytes[0]&255)!=255||(bytes[1]&255)!=216||(bytes[bytes.length-2]&255)!=255||(bytes[bytes.length-1]&255)!=217)throw new IOException("JPEG signature/end");
        try(var input=ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Image decoder missing");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input,true,true);
                if((long)reader.getWidth(0)*reader.getHeight(0)>25_000_000)throw new IOException("Image dimensions limit");
                final boolean[] warning={false};reader.addIIOReadWarningListener((r,w)->warning[0]=true);
                if(reader.read(0)==null||warning[0])throw new IOException("Malformed image");
            } finally {reader.dispose();}
        }
    }
    private static void pdf(Path path,byte[] prefix) throws IOException {
        if(!new String(prefix,StandardCharsets.ISO_8859_1).startsWith("%PDF-"))throw new IOException("PDF signature");
        byte[] bytes=Files.readAllBytes(path);
        String tail=new String(bytes,Math.max(0,bytes.length-1024),Math.min(bytes.length,1024),StandardCharsets.ISO_8859_1).stripTrailing();
        if(!tail.endsWith("%%EOF"))throw new IOException("PDF truncated");
        try(var source=new org.apache.pdfbox.io.RandomAccessReadBufferedFile(path.toFile())) {
        var parser=new org.apache.pdfbox.pdfparser.PDFParser(source);
        try(var document=parser.parse(false)) {
            if(document.isEncrypted()||document.getNumberOfPages()<1||document.getNumberOfPages()>1000)throw new IOException("PDF page/encryption policy");
            long expanded=0;byte[] buffer=new byte[8192];
            for(var page:document.getPages())try(InputStream content=page.getContents()){
                int n;while((n=content.read(buffer))!=-1){expanded+=n;if(expanded>80L*1024*1024)throw new IOException("PDF expansion limit");}
            }
        }
        }
    }
    private static void office(Path path,String ext) throws Exception {
        // ZipFile is deliberately permissive about prepended/trailing junk; reject it for Office uploads.
        try(var source=new RandomAccessFile(path.toFile(),"r")) {
            if(source.length()<22||source.readInt()!=0x504b0304)throw new IOException("ZIP signature");
            int tailSize=(int)Math.min(source.length(),65557);byte[] tail=new byte[tailSize];source.seek(source.length()-tailSize);source.readFully(tail);
            boolean ended=false;
            for(int i=tail.length-22;i>=0;i--)if(tail[i]==0x50&&tail[i+1]==0x4b&&tail[i+2]==5&&tail[i+3]==6){
                int comment=(tail[i+20]&255)|((tail[i+21]&255)<<8);
                if(i+22+comment==tail.length){ended=true;break;}
            }
            if(!ended)throw new IOException("ZIP truncated or trailing data");
        }
        Set<String> names=new HashSet<>(); long total=0; int count=0;
        byte[] types=null,main=null; boolean worksheet=false;
        try(ZipFile zip=new ZipFile(path.toFile(),StandardCharsets.UTF_8)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(++count>2000||!names.add(name)||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/")).contains("..")||name.contains(":"))throw new IOException("Unsafe ZIP entry");
                if(name.toLowerCase(Locale.ROOT).contains("vbaproject")||name.endsWith(".bin"))throw new IOException("Embedded binaries prohibited");
                if(entry.isDirectory())continue;
                long length=0; CRC32 crc=new CRC32();ByteArrayOutputStream xml=new ByteArrayOutputStream();
                boolean isXml=name.endsWith(".xml")||name.endsWith(".rels");
                try(InputStream in=zip.getInputStream(entry)) {
                    byte[] buffer=new byte[8192]; int n;
                    while((n=in.read(buffer))!=-1) {
                        total+=n;length+=n;if(total>80L*1024*1024||(isXml&&length>10L*1024*1024))throw new IOException("ZIP expansion limit");
                        crc.update(buffer,0,n);if(isXml)xml.write(buffer,0,n);
                    }
                }
                if(entry.getSize()!=length||entry.getCrc()!=crc.getValue())throw new IOException("ZIP integrity");
                if(isXml) {
                    byte[] content=xml.toByteArray(); parseXml(content);
                    if(name.equals("[Content_Types].xml"))types=content;
                    if(name.equals(ext.equals("docx")?"word/document.xml":"xl/workbook.xml"))main=content;
                    if(name.startsWith("xl/worksheets/")&&name.endsWith(".xml"))worksheet=true;
                }
            }
        }
        if(types==null||main==null||!names.contains("_rels/.rels"))throw new IOException("Missing Office package parts");
        var typeDoc=parseXml(types);boolean matching=false;
        var overrides=typeDoc.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/content-types","Override");
        String expected=ext.equals("docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
        for(int i=0;i<overrides.getLength();i++) {
            var item=(org.w3c.dom.Element)overrides.item(i);
            if(item.getAttribute("ContentType").equals(expected)&&item.getAttribute("PartName").equals(ext.equals("docx")?"/word/document.xml":"/xl/workbook.xml"))matching=true;
        }
        var root=parseXml(main).getDocumentElement();
        if(!matching||!root.getLocalName().equals(ext.equals("docx")?"document":"workbook")||!root.getNamespaceURI().equals(ext.equals("docx")?"http://schemas.openxmlformats.org/wordprocessingml/2006/main":"http://schemas.openxmlformats.org/spreadsheetml/2006/main")||ext.equals("xlsx")&&!worksheet)throw new IOException("Office type mismatch");
    }
    private static org.w3c.dom.Document parseXml(byte[] bytes) throws Exception {
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        var builder=f.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});
        return builder.parse(new ByteArrayInputStream(bytes));
    }
}
