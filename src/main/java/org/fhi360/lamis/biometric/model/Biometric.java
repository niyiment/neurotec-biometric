package org.fhi360.lamis.biometric.model;

import lombok.Data;

import java.util.List;

@Data
public class Biometric {
    List<byte[]> templates;
}
