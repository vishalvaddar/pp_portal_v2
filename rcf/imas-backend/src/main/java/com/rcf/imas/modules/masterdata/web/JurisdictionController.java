package com.rcf.imas.modules.masterdata.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rcf.imas.modules.masterdata.persistence.JurisdictionRepository;
import com.rcf.imas.platform.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
// Node's jurisdiction/districts/institutes/juris-names mounts had NO auth (public). These are read-only
// geography/reference lookups consumed by admin screens (via useJurisData) today; gated isAuthenticated()
// -- role-agnostic so no authenticated caller is ever 403'd -- rather than hasRole('ADMIN'), since locking
// low-sensitivity reference data to ADMIN risks breaking any shared/non-admin consumer. Stricter than Node.
@PreAuthorize("isAuthenticated()")
class JurisdictionController {

    record JurisNamesRequest(@JsonProperty("districtIds") List<Long> districtIds,
                             @JsonProperty("blockIds") List<Long> blockIds,
                             @JsonProperty("instituteIds") List<String> instituteIds) {}

    private final JurisdictionRepository repo;

    JurisdictionController(JurisdictionRepository repo) { this.repo = repo; }

    @GetMapping("/states")
    public List<Map<String, Object>> states() { return repo.states(); }

    @GetMapping("/divisions-by-state/{stateId}")
    public List<Map<String, Object>> divisions(@PathVariable String stateId) {
        return repo.childrenOf("DIVISION", stateId);
    }

    @GetMapping("/districts-by-division/{divisionId}")
    public List<Map<String, Object>> districts(@PathVariable String divisionId) {
        return repo.childrenOf("EDUCATION DISTRICT", divisionId);
    }

    @GetMapping("/blocks-by-district/{districtId}")
    public List<Map<String, Object>> blocks(@PathVariable String districtId) {
        return repo.childrenOf("BLOCK", districtId);
    }

    @GetMapping("/clusters-by-block/{blockId}")
    public List<Map<String, Object>> clusters(@PathVariable String blockId) {
        return repo.childrenOf("CLUSTER", blockId);
    }

    @GetMapping("/institutes-by-cluster/{clusterId}")
    public List<Map<String, Object>> institutesByCluster(@PathVariable String clusterId) {
        return repo.institutesByCluster(clusterId);
    }

    @GetMapping("/juris-name/{jurisCode}")
    public Map<String, Object> jurisName(@PathVariable String jurisCode) {
        // Node returns rows[0]; missing code returned undefined -> empty body. Preserve leniently:
        return repo.jurisName(jurisCode).orElse(Map.of());
    }

    @GetMapping("/districts/all")
    public List<Map<String, Object>> allDistricts() { return repo.allDistricts(); }

    @GetMapping("/institutes/all")
    public List<Map<String, Object>> allInstitutes() { return repo.allInstituteNames(); }

    @GetMapping("/institutes/search")
    public List<Map<String, Object>> searchInstitutes(@RequestParam(required = false) String query) {
        if (query == null || query.isBlank()) {
            throw ApiException.error(400, "Missing query parameter");
        }
        return repo.searchInstitutes(query);
    }

    @PostMapping("/juris-names")
    public Map<String, Object> jurisNames(@RequestBody JurisNamesRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("districts", repo.namesByType("EDUCATION DISTRICT", req.districtIds()));
        body.put("blocks", repo.namesByType("BLOCK", req.blockIds()));
        body.put("institutes", repo.instituteNamesByDise(req.instituteIds()));
        return body;
    }
}
