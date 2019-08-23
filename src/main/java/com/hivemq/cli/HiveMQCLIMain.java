package com.hivemq.cli;

import com.hivemq.cli.ioc.DaggerHiveMQCLI;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.Logger;
import org.pmw.tinylog.labelers.TimestampLabeler;
import org.pmw.tinylog.policies.SizePolicy;
import org.pmw.tinylog.writers.ConsoleWriter;
import org.pmw.tinylog.writers.RollingFileWriter;
import picocli.CommandLine;

import java.security.Security;

public class HiveMQCLIMain {

    public static void main(final String[] args) {

        Security.setProperty("crypto.policy", "unlimited");

        final CommandLine commandLine = DaggerHiveMQCLI.create().commandLine();

        Configurator.defaultConfig()
                .writer(new ConsoleWriter())
                .formatPattern("Client {context:identifier}: {message}")
                .level(Level.INFO)
                .activate();

        if (args.length == 0) {
            Logger.info(commandLine.getUsageMessage());
            System.exit(0);
        }

        final int exitCode = commandLine.execute(args);

        System.exit(exitCode);

    }

}