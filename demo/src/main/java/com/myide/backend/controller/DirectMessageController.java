package com.myide.backend.controller;
import com.myide.backend.service.CurrentUserService;
import com.myide.backend.service.DirectMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class DirectMessageController {
    private final DirectMessageService service;
    private final CurrentUserService current;
    @GetMapping("/conversations")
    public DirectMessageService.Slice<DirectMessageService.Conversation> list(@RequestParam(defaultValue="0") int page) {
        return service.list(current.getCurrentUserId(),page);
    }
    @PostMapping("/conversations")
    public DirectMessageService.Conversation start(@RequestBody DirectMessageService.StartRequest request) {
        return service.start(current.getCurrentUserId(),request);
    }
    @GetMapping("/conversations/{id}")
    public DirectMessageService.Conversation detail(@PathVariable long id) { return service.detail(current.getCurrentUserId(),id); }
    @GetMapping("/unread")
    public Map<String,Long> unread() { return Map.of("count",service.unread(current.getCurrentUserId())); }
    @GetMapping("/conversations/{id}/messages")
    public DirectMessageService.Slice<DirectMessageService.Message> history(@PathVariable long id,@RequestParam(required=false) Long before) {
        return service.history(current.getCurrentUserId(),id,before);
    }
    @PostMapping("/conversations/{id}/messages")
    public DirectMessageService.Message send(@PathVariable long id,@RequestBody DirectMessageService.SendRequest request) {
        return service.send(current.getCurrentUserId(),id,request);
    }
    @PostMapping("/conversations/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id,@RequestBody DirectMessageService.ReadRequest request) {
        service.read(current.getCurrentUserId(),id,request); return ResponseEntity.noContent().build();
    }
}