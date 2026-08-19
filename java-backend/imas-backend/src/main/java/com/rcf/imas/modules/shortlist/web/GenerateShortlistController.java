package com.rcf.imas.modules.shortlist.web;

import com.rcf.imas.modules.shortlist.persistence.ShortlistReadRepository;
import com.rcf.imas.modules.shortlist.service.ShortlistService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shortlist/generate")
@PreAuthorize("hasRole('ADMIN')")   // audit CRITICAL: shortlisting screens were open in Node
class GenerateShortlistController {

    private final ShortlistReadRepository reads;
    private final ShortlistService service;

    GenerateShortlistController(ShortlistReadRepository reads, ShortlistService service) {
        this.reads = reads;
        this.service = service;
    }

    @GetMapping("/allstates")
    public List<Map<String, Object>> allStates() { return reads.allStates(); }

    @GetMapping("/divisions/{stateName}")
    public List<Map<String, Object>> divisions(@PathVariable String stateName) { return reads.divisionsByState(stateName); }

    @GetMapping("/districts/{divisionName}")
    public List<Map<String, Object>> districts(@PathVariable String divisionName) { return reads.districtsByDivision(divisionName); }

    @GetMapping("/blocks/{stateName}/{divisionName}/{districtName}/{year}")
    public List<Map<String, Object>> blocks(@PathVariable String stateName, @PathVariable String divisionName,
                                            @PathVariable String districtName, @PathVariable String year) {
        return reads.blocksByDistrict(stateName, divisionName, districtName, year);
    }

    @GetMapping("/criteria")
    public List<Map<String, Object>> criteria() { return reads.criteria(); }

    @PostMapping("/start-shortlist")
    public Map<String, Object> startShortlist(@RequestBody Map<String, Object> body) {
        return service.startShortlisting(body);
    }
}
