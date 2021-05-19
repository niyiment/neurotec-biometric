package org.fhi360.lamis.biometric.model;

import lombok.Data;

@Data
public class BiometricRequest {
    private Long facilityId;
    private Long patientId;
    private String finger;
    private byte[] template;
}
