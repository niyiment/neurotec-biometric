
package org.fhi360.lamis.biometric.model;

import lombok.Data;

@Data
public class BiometricResult {
    private String id;
    private String message;
    private byte[] template;
    private boolean identified;
    private boolean iso;
}
