package org.fhi360.lamis.biometric.web;

import com.neurotec.biometrics.*;
import com.neurotec.biometrics.client.NBiometricClient;
import com.neurotec.biometrics.standards.CBEFFBDBFormatIdentifiers;
import com.neurotec.biometrics.standards.CBEFFBiometricOrganizations;
import com.neurotec.biometrics.standards.FMRecord;
import com.neurotec.devices.NDevice;
import com.neurotec.devices.NDeviceManager;
import com.neurotec.devices.NDeviceType;
import com.neurotec.devices.NFScanner;
import com.neurotec.io.NBuffer;
import com.neurotec.licensing.NLicense;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.fhi360.lamis.biometric.model.Biometric;
import org.fhi360.lamis.biometric.model.BiometricResult;
import org.fhi360.lamis.biometric.model.BiometricTemplate;
import org.fhi360.lamis.biometric.model.Device;
import org.fhi360.lamis.biometric.util.LibraryManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

@RestController
@RequestMapping("/api/biometrics")
@Slf4j
@CrossOrigin(origins = "*")
public class BiometricResource {
    private final RestTemplate restTemplate = new RestTemplate();
    private final static String BASE_URL = "/api/biometrics";
    private NDeviceManager deviceManager;
    private NBiometricClient client;
    private List<BiometricTemplate> templates = new ArrayList<>();
    private boolean templatesUpdated;


    @GetMapping("/readers")
    public List<Device> getReaders() {
        List<Device> devices = new ArrayList<>();
        getDevices().forEach(device -> {
            Device d = new Device();
            d.setName(device.getDisplayName());
            d.setId(device.getId());
            devices.add(d);
        });
        return devices;
    }

    @PostMapping("/identify")
    public BiometricResult identify(@RequestParam String reader, @RequestParam String accessToken,
                                    @RequestParam String server, @RequestBody Biometric biometric) {
        LOG.info("STARTED CAPTURING +++++++++++++++++");
        try {
            reader = URLDecoder.decode(reader, StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException ignored) {

        }
        NSubject subject = new NSubject();
        NFinger finger = new NFinger();
        finger.setPosition(NFPosition.UNKNOWN);
        subject.getFingers().add(finger);
        if (scannerIsNotSet(reader)) {
            return null;
        }

        NBiometricStatus status = client.capture(subject);
        BiometricResult result = new BiometricResult();
        if (status.equals(NBiometricStatus.OK)) {
            status = client.createTemplate(subject);
            if (status.equals(NBiometricStatus.OK)) {
                List<NSubject> galleries = new ArrayList<>();
                if (biometric == null || biometric.getTemplates() == null || biometric.getTemplates().isEmpty()) {
                    LOG.info("Querying server for templates...");
                    Instant start = Instant.now();
                    templates = biometricTemplates(server, accessToken);
                    new Thread(() -> updateTemplates(server)).start();
                    LOG.info("Query Duration: {} secs", Duration.between(start, Instant.now()).getSeconds());
                }
                templates.forEach(template -> {
                    if (template.getTemplate().length > 0) {
                        NSubject gallery = new NSubject();
                        gallery.setTemplateBuffer(new NBuffer(template.getTemplate()));
                        gallery.setId(template.getId());
                        galleries.add(gallery);
                    }
                });
                LOG.info("Starting identification with total templates {}...", templates.size());
                Instant start = Instant.now();
                LOG.info("Starting Enrollment...");
                Instant startE = Instant.now();
                NBiometricTask task = client.createTask(
                        EnumSet.of(NBiometricOperation.ENROLL), null);
                task.getSubjects().addAll(galleries);
                client.performTask(task);
                LOG.info("Enrollment Duration: {} secs", Duration.between(startE, Instant.now()).getSeconds());
                if (task.getStatus().equals(NBiometricStatus.OK)) {
                    status = client.identify(subject);
                    if (status.equals(NBiometricStatus.OK)) {
                        String id = subject.getMatchingResults().get(0).getId();
                        result.setId(id);
                        result.setMessage("PATIENT_IDENTIFIED");
                    } else {
                        result.setMessage("PATIENT_NOT_IDENTIFIED");
                        byte[] isoTemplate = subject.getTemplateBuffer(CBEFFBiometricOrganizations.ISO_IEC_JTC_1_SC_37_BIOMETRICS,
                                CBEFFBDBFormatIdentifiers.ISO_IEC_JTC_1_SC_37_BIOMETRICS_FINGER_MINUTIAE_RECORD_FORMAT,
                                FMRecord.VERSION_ISO_CURRENT).toByteArray();
                        result.setTemplate(isoTemplate);
                        result.setIso(true);

                        BiometricTemplate template = new BiometricTemplate();
                        template.setIso(true);
                        template.setTemplate(isoTemplate);
                        template.setId("currentUser_" + RandomStringUtils.randomAlphabetic(5));
                        templates.add(template);
                    }
                } else {
                    result.setMessage("COULD_NOT_ENROLL_TEMPLATES");
                }
                LOG.info("Identification Duration: {} secs", Duration.between(start, Instant.now()).getSeconds());
            } else {
                result.setMessage("COULD_NOT_CREATE_TEMPLATE");
            }
        } else {
            result.setMessage("COULD_NOT_CAPTURE_TEMPLATE");
        }
        client.clear();
        return result;
    }

    @PostMapping("/enrol")
    public BiometricResult enrol(@RequestParam String reader, @RequestParam String server, @RequestParam String accessToken,
                                 @RequestBody Biometric biometric) {
        try {
            reader = URLDecoder.decode(reader, "UTF-8");
        } catch (UnsupportedEncodingException ignored) {
        }
        if (scannerIsNotSet(reader)) {
            return null;
        }

        return identify(accessToken, reader, server, biometric);
    }


    @SneakyThrows
    private List<BiometricTemplate> biometricTemplates(String server, String accessToken) {
        String url = server + "/biometrics";
        if (!url.startsWith("http://")) {
            url = "http://" + url;
        }
        ResponseEntity<List<BiometricTemplate>> response = restTemplate.exchange(
                RequestEntity.get(new URI(url)).header("Authorization", "Bearer " + accessToken)
                        .header("Content-Type", "application/json").build(),
                new ParameterizedTypeReference<List<BiometricTemplate>>() {
                });
        return response.getBody();
    }

    private void updateTemplates(String server) {
        String url = server + "/biometrics/update-templates";
        if (!url.startsWith("http://")) {
            url = "http://" + url;
        }
        List<BiometricTemplate> templates = biometricTemplates(server, "");
        if (!templatesUpdated) {
            JSONArray tplts = new JSONArray();
            templates.stream()
                    .filter(t -> !t.getIso())
                    .forEach(t -> {
                        try {
                            NSubject subject = new NSubject();
                            subject.setTemplateBuffer(new NBuffer(t.getTemplate()));
                            byte[] isoTemplate = subject.getTemplateBuffer(CBEFFBiometricOrganizations.ISO_IEC_JTC_1_SC_37_BIOMETRICS,
                                    CBEFFBDBFormatIdentifiers.ISO_IEC_JTC_1_SC_37_BIOMETRICS_FINGER_MINUTIAE_RECORD_FORMAT,
                                    FMRecord.VERSION_ISO_CURRENT).toByteArray();
                            JSONObject data = new JSONObject();
                            data.put("id", t.getId());
                            data.put("template", isoTemplate);
                            tplts.put(data);
                        } catch (Exception ignored) {

                        }
                    });
            HttpHeaders headers = new HttpHeaders();
            headers.add("Content-Type", "application/json");
            LOG.info("Templates: {}", tplts.length());
            HttpEntity<String> request = new HttpEntity<>(tplts.toString(), headers);
            restTemplate.postForEntity(url, request, Void.class);
            templatesUpdated = true;
        }
    }

    @GetMapping
    public List<BiometricTemplate> getTemplates(@RequestParam String server, @RequestParam String token) {
        return biometricTemplates(server, token);
    }

    private boolean scannerIsNotSet(String reader) {
        for (NDevice device : getDevices()) {
            if (device.getId().equals(reader)) {
                client.setFingerScanner((NFScanner) device);
                return false;
            }
        }
        return true;
    }

    private void initDeviceManager() {
        deviceManager = new NDeviceManager();
        deviceManager.setDeviceTypes(EnumSet.of(NDeviceType.FINGER_SCANNER));
        deviceManager.setAutoPlug(true);
        deviceManager.initialize();
    }

    private NDeviceManager.DeviceCollection getDevices() {
        return deviceManager.getDevices();
    }

    private void obtainLicense(String component) {
        try {
            boolean result = NLicense.obtainComponents("/local", "5000", component);
            LOG.info("Obtaining license: {}: {}", component, result);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private void releaseLicense(String component) {
        try {
            NLicense.release(component);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createClient() {
        client = new NBiometricClient();
        client.setMatchingThreshold(60);
        client.setFingersMatchingSpeed(NMatchingSpeed.LOW);
        client.setFingersTemplateSize(NTemplateSize.LARGE);
        client.initialize();
    }

    @PostConstruct
    public void init() {
        LibraryManager.initLibraryPath();
        initDeviceManager();

        obtainLicense("Biometrics.FingerExtraction");
        obtainLicense("Biometrics.Standards.FingerTemplates");
        obtainLicense("Biometrics.FingerMatching");

        createClient();

        try {
            updateTemplates("http://localhost");
        } catch (Exception ignored) {

        }

        try {
            updateTemplates("http://localhost:8080");
        } catch (Exception ignored) {

        }
    }
}
