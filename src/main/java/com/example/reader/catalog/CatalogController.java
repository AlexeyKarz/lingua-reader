package com.example.reader.catalog;

import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    // TODO replace with DB later (Postgres)
    @GetMapping("/books")
    public List<Map<String,Object>> books() {
        return List.of(
                Map.of("id","faust","title","Faust","langs", List.of("en","de")),
                Map.of("id","quijote","title","Don Quixote","langs", List.of("es","en"))
        );
    }
}
