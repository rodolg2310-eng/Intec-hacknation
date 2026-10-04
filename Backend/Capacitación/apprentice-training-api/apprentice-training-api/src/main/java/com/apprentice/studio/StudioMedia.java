package com.apprentice.studio;
import com.apprentice.common.ApiException;
import com.apprentice.tenant.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
@Service
public class StudioMedia {
  private final Path root;
  private final String ffmpeg;
  private final MediaChunkRepository chunks;
  public StudioMedia(@Value("${apprentice.data-dir}") String dir,@Value("${FFMPEG_PATH:ffmpeg}") String ffmpeg,MediaChunkRepository chunks){root=Path.of(dir).toAbsolutePath().normalize().resolve("media");this.ffmpeg=ffmpeg;this.chunks=chunks;}
  Path directory(UUID session){return root.resolve(TenantContext.getId().toString()).resolve(session.toString());}
  Path image(UUID session,UUID event){return directory(session).resolve(event+".jpg");}
  Path cameraImage(UUID session,UUID event){return directory(session).resolve("camera-"+event+".jpg");}
  byte[] decode(String input){
    if(input==null||input.length()>2800000||!input.matches("^data:image/(jpeg|png);base64,[A-Za-z0-9+/=\\r\\n]+$"))throw ApiException.badRequest("Send a JPEG or PNG screenshot up to 2 MB.");
    try{byte[] bytes=Base64.getDecoder().decode(input.split(",",2)[1]);BufferedImage img=ImageIO.read(new ByteArrayInputStream(bytes));if(img==null||img.getWidth()>4096||img.getHeight()>4096)throw ApiException.badRequest("Invalid screenshot.");return bytes;}catch(IOException|IllegalArgumentException e){throw ApiException.badRequest("Invalid screenshot.");}
  }
  void image(UUID session,UUID event,byte[] bytes){
    image(image(session,event),bytes);
  }
  void image(Path p,byte[] bytes){
    try{p=checked(p.toString());Files.createDirectories(p.getParent());BufferedImage img=ImageIO.read(new ByteArrayInputStream(bytes));BufferedImage rgb=new BufferedImage(img.getWidth(),img.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=rgb.createGraphics();g.drawImage(img,0,0,Color.WHITE,null);g.dispose();ImageIO.write(rgb,"jpg",p.toFile());}catch(IOException e){throw new IllegalStateException("Could not persist screenshot.",e);}
  }
  String dataUrl(FrameJob f){return dataUrl(f.imagePath);}
  String dataUrl(String path){try{return "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(Files.readAllBytes(checked(path)));}catch(IOException e){throw new IllegalStateException("Screenshot is missing.",e);}}
  Path checked(String path){
    Path tenantRoot=root.resolve(TenantContext.getId().toString()).toAbsolutePath().normalize();
    Path p=Path.of(path).toAbsolutePath().normalize();
    if(!p.startsWith(tenantRoot)){
      String portable=path.replace('\\','/');
      int media=portable.toLowerCase(Locale.ROOT).lastIndexOf("/media/");
      if(media<0)throw ApiException.badRequest("Invalid media path.");
      p=root.resolve(portable.substring(media+"/media/".length())).toAbsolutePath().normalize();
    }
    if(!p.startsWith(tenantRoot))throw ApiException.badRequest("Invalid media path.");
    return p;
  }
  Map<String,Object> upload(UUID session,UUID recording,long sequence,double start,double end,String kind,MultipartFile file){
    if(sequence<0||sequence>10000||!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start||end>5400||!Set.of("video","audio").contains(kind)||file.isEmpty()||file.getSize()>16*1024*1024)throw ApiException.badRequest("Invalid recording fragment.");
    try{
      byte[] bytes=file.getBytes();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      MediaChunk existing=chunks.findBySessionIdAndRecordingIdAndSequence(session,recording,sequence).orElse(null);
      if(existing!=null){if(!existing.checksum.equals(hash))throw ApiException.conflict("This fragment sequence already contains different bytes.");return Map.of("id",existing.id,"sequence",sequence,"received",true);}
      MediaChunk c=new MediaChunk();c.sessionId=session;c.recordingId=recording;c.sequence=sequence;c.startSeconds=start;c.endSeconds=end;c.kind=kind;c.contentType="audio".equals(kind)?"audio/webm":"video/webm";c.checksum=hash;
      Path p=directory(session).resolve(recording+"-"+sequence+".part");Files.createDirectories(p.getParent());Files.write(p,bytes);c.path=p.toString();chunks.save(c);return Map.of("id",c.id,"sequence",sequence,"received",true);
    }catch(IOException|GeneralSecurityException e){throw new IllegalStateException("Recording fragment could not be saved.",e);}
  }
  List<MediaChunk> received(UUID session,UUID recording){return chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session).stream().filter(c->c.recordingId.equals(recording)&&!c.kind.endsWith("-final")&&!c.removed).sorted(Comparator.comparingLong(c->c.sequence)).toList();}
  boolean hasFragments(UUID session,UUID recording){return chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session).stream().anyMatch(c->c.recordingId.equals(recording));}
  void close(UUID session,UUID recording,long count,double end){
    if(!Double.isFinite(end)||end<0||end>5400)throw ApiException.badRequest("Invalid recording end time.");
    List<MediaChunk> parts=chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session).stream().filter(c->c.recordingId.equals(recording)&&!c.kind.endsWith("-final")).sorted(Comparator.comparingLong(c->c.sequence)).toList();
    if(count<=0||parts.size()!=count)throw ApiException.conflict("Recording fragments are still missing. Retry the upload before finishing.");
    for(int i=0;i<parts.size();i++)if(parts.get(i).sequence!=i)throw ApiException.conflict("Recording has a missing fragment.");
    MediaChunk last=parts.get(parts.size()-1);if(end<last.startSeconds)throw ApiException.badRequest("Recording end precedes its final fragment.");last.finalChunk=true;last.endSeconds=end;chunks.save(last);
  }
  void consolidate(UUID session,JsonNode removedRanges){
    Map<UUID,List<MediaChunk>> groups=new LinkedHashMap<>();
    List<MediaChunk> all=chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session);
    for(MediaChunk c:all)if(!c.kind.endsWith("-final")&&!c.removed)groups.computeIfAbsent(c.recordingId,x->new ArrayList<>()).add(c);
    for(var entry:groups.entrySet()){
      if(all.stream().anyMatch(c->c.recordingId.equals(entry.getKey())&&c.kind.endsWith("-final")&&!c.removed))continue;
      List<MediaChunk> parts=entry.getValue();parts.sort(Comparator.comparingLong(c->c.sequence));
      if(!parts.get(parts.size()-1).finalChunk)throw ApiException.conflict("A recording has not finished uploading.");
      for(int i=0;i<parts.size();i++)if(parts.get(i).sequence!=i)throw ApiException.conflict("A recording fragment is missing.");
      Path input=directory(session).resolve(entry.getKey()+".webm");
      try(OutputStream out=Files.newOutputStream(input)){for(MediaChunk c:parts)Files.copy(checked(c.path),out);}catch(IOException e){throw new IllegalStateException("Could not assemble recording.",e);}
      MediaChunk first=parts.get(0),last=parts.get(parts.size()-1);
      Path output=directory(session).resolve(entry.getKey()+(first.kind.equals("video")?".mp4":".m4a"));
      List<String> args=new ArrayList<>(List.of(ffmpeg,"-hide_banner","-loglevel","error","-y","-i",input.toString()));
      filters(args,removedRanges,first.startSeconds,first.kind.equals("video"));
      if(first.kind.equals("video"))args.addAll(List.of("-c:v","libx264","-preset","veryfast","-crf","23","-pix_fmt","yuv420p"));else args.add("-vn");
      args.addAll(List.of("-c:a","aac","-movflags","+faststart",output.toString()));run(args,directory(session));
      MediaChunk finalFile=new MediaChunk();finalFile.sessionId=session;finalFile.recordingId=entry.getKey();finalFile.sequence=-1;finalFile.startSeconds=first.startSeconds;finalFile.endSeconds=last.endSeconds;finalFile.kind=first.kind+"-final";finalFile.path=output.toString();finalFile.contentType=first.kind.equals("video")?"video/mp4":"audio/mp4";finalFile.checksum="finalized";finalFile.finalChunk=true;chunks.save(finalFile);
      try{Files.deleteIfExists(input);for(MediaChunk c:parts){Files.deleteIfExists(checked(c.path));c.removed=true;chunks.save(c);}}catch(IOException e){throw new IllegalStateException("Could not remove intermediate recordings.",e);}
    }
  }
  private void filters(List<String> args,JsonNode ranges,double offset,boolean video){
    List<String> vf=new ArrayList<>(),af=new ArrayList<>();
    for(JsonNode range:ranges){double a=Math.max(0,range.path("start").asDouble()-offset),b=range.path("end").asDouble()-offset;if(b<=a)continue;String condition=String.format(Locale.ROOT,"between(t,%.3f,%.3f)",a,b);if(video)vf.add("drawbox=x=0:y=0:w=iw:h=ih:color=black:t=fill:enable='"+condition+"'");af.add("volume=0:enable='"+condition+"'");}
    if(!vf.isEmpty())args.addAll(List.of("-vf",String.join(",",vf)));if(!af.isEmpty())args.addAll(List.of("-af",String.join(",",af)));
  }
  void redact(UUID session,double start,double end){
    for(MediaChunk c:chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session))if(c.kind.endsWith("-final")&&!c.removed&&c.startSeconds<end&&c.endSeconds>start){
      Path original=checked(c.path),temp=original.resolveSibling("redacted-"+original.getFileName());
      var ranges=new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode();ranges.addObject().put("start",start).put("end",end);
      List<String> args=new ArrayList<>(List.of(ffmpeg,"-hide_banner","-loglevel","error","-y","-i",original.toString()));filters(args,ranges,c.startSeconds,c.kind.startsWith("video"));
      if(c.kind.startsWith("video"))args.addAll(List.of("-c:v","libx264","-preset","veryfast","-pix_fmt","yuv420p"));else args.add("-vn");args.addAll(List.of("-c:a","aac","-movflags","+faststart",temp.toString()));run(args,original.getParent());
      try{Files.move(temp,original,StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new IllegalStateException("Could not replace removed recording evidence.",e);}
    }
  }
  private void run(List<String> args,Path dir){
    try{Path log=dir.resolve("encoding.log");Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();if(!p.waitFor(300,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Recording finalization timed out.");}if(p.exitValue()!=0)throw new IllegalStateException("Recording finalization failed. Check FFmpeg and the received fragments.");}catch(IOException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("FFmpeg is unavailable or recording finalization was interrupted.",e);}
  }
  List<Map<String,Object>> manifest(UUID session,String base){return chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session).stream().filter(c->c.kind.endsWith("-final")&&!c.removed).map(c->Map.<String,Object>of("id",c.id,"kind",c.kind,"startSeconds",c.startSeconds,"endSeconds",c.endSeconds,"url",base+"/recordings/"+c.id)).toList();}
  boolean pending(UUID session){return chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session).stream().anyMatch(c->!c.kind.endsWith("-final")&&!c.removed);}
  MediaChunk file(UUID session,UUID id){return chunks.findById(id).filter(c->c.getTenantId().equals(TenantContext.getId())&&c.sessionId.equals(session)&&c.kind.endsWith("-final")&&!c.removed).orElseThrow(()->ApiException.notFound("Recording"));}
  void erase(UUID session){Path dir=directory(session).toAbsolutePath().normalize();if(!dir.startsWith(root.resolve(TenantContext.getId().toString()))||dir.equals(root))throw ApiException.badRequest("Invalid recording directory.");try{if(Files.exists(dir))try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}catch(IOException e){throw new IllegalStateException("Could not erase media.",e);}chunks.deleteAll(chunks.findBySessionIdOrderByStartSecondsAscSequenceAsc(session));}
}
