package org.fhi360.lamis.biometric.model;

import lombok.Data;

@Data
public class BiometricTemplate {
    private String id;
    private Boolean iso;
    private byte[] template;
}
