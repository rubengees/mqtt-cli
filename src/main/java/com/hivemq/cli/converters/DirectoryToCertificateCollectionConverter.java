package com.hivemq.cli.converters;

import com.hivemq.cli.utils.CertificateConverterUtils;
import org.jetbrains.annotations.NotNull;
import picocli.CommandLine;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;

public class DirectoryToCertificateCollectionConverter implements CommandLine.ITypeConverter<Collection<X509Certificate>> {
    static final String DIRECTORY_NOT_FOUND = "The given directory was not found.";
    static final String NOT_A_DIRECTORY = "The given path is not a valid directory";
    static final String NO_CERTIFICATES_FOUND_IN_DIRECTORY = "The given directory does not contain any valid certificates";

    @Override
    public Collection<X509Certificate> convert(final @NotNull String s) throws Exception {

        final File directory = new File(s);

        if (!directory.exists())
            throw new FileNotFoundException(DIRECTORY_NOT_FOUND);

        if (!directory.isDirectory())
            throw new IllegalArgumentException(NOT_A_DIRECTORY);

        final File[] validFiles = directory.listFiles((dir, name) -> CertificateConverterUtils.endsWithValidExtension(name));

        if (validFiles == null || validFiles.length == 0)
            throw new IllegalArgumentException(NO_CERTIFICATES_FOUND_IN_DIRECTORY);

        final Collection<X509Certificate> certificates = new ArrayList<>();

        for (final File validFile : validFiles) {
            certificates.add(CertificateConverterUtils.generateX509Certificate(validFile));
        }

        return certificates;
    }


}