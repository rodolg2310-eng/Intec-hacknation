package com.apprentice.studio;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public class HealthController {
  @GetMapping("/api/health") public Map<String,Object> health(){return Map.of("ok",true,"service","Traina","reasoning","Anthropic","speech","ElevenLabs Agent");}
}
