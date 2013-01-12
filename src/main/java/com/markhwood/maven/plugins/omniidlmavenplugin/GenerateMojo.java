/*
 * Copyright 2013 Mark H. Wood.
 */

package com.markhwood.maven.plugins.omniidlmavenplugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generate C++ sources from IDL.
 *
 * @author Mark H. Wood
 */
@Mojo( name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES )
public class GenerateMojo
    extends AbstractMojo
{
    /** Directories containing IDL files to be compiled. */
    @Parameter( required = true )
    private File[] inputs; // TODO needs default

    /** Where to place generated C++ sources. */
    @Parameter( defaultValue = "${project.build.directory}/generated-sources/c++",
            required = true )
    private File outputDirectory;

    /** Use the preprocessor? */
    @Parameter( defaultValue = "true", required = true )
    private Boolean preprocess;

    /** Non-default preprocessor command. */
    @Parameter
    private String preprocessorCommand;

    /** Arguments for the preprocessor stage. */
    @Parameter
    private String[] preprocessorArguments;

    /** Define preprocessor symbols. */
    @Parameter
    private Map<String, String> defines; // FIXME map?

    /** Undefine preprocessor symbols. */
    @Parameter
    private String[] undefines;

    /** Directories to be searched for preprocessor inclusions. */
    @Parameter
    private File[] includeDirs; // FIXME File or String?

    /** Select the back end processor. */
    @Parameter( defaultValue = "cxx", required = true )
    private String backend;

    /** Arguments to the back end. */
    @Parameter
    private String[] backendArguments;

    /** Path to the back ends. */
    @Parameter
    private String backendsPath;

    /** Do not warn about unresolved forward declarations. */
    @Parameter( defaultValue = "false" )
    private Boolean allowUnresolvedForward;

    /** Do not treat identifiers differing only in  case as an error. */
    @Parameter( defaultValue = "false" )
    private Boolean allowCaseDifference;

    /** Pass comments after declarations to the back end. */
    @Parameter( defaultValue = "false" )
    private Boolean passCommentsAfter;

    /** Pass comments before declarations to the back end. */
    @Parameter( defaultValue = "false" )
    private Boolean passCommentsBefore;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        List<String> arguments = new ArrayList<String>();
        StringBuilder option = new StringBuilder();

        arguments.add("omniidl");
        arguments.add("-b" + backend);

        if (!preprocess)
        {
            arguments.add("-N");
        }

        if (null != preprocessorCommand && !preprocessorCommand.isEmpty())
        {
            arguments.add("-Y" + preprocessorCommand);
        }

        if ((null != preprocessorArguments) && (preprocessorArguments.length > 0))
        {
            option.setLength(0);
            option.append("-Wp");
            for (int ppArg = 0; ppArg < preprocessorArguments.length; ppArg++)
            {
                if (ppArg > 0)
                {
                    option.append(',');
                }
                option.append(preprocessorArguments[ppArg]);
            }
            arguments.add(option.toString());
        }

        if (null != defines)
        {
            for (Entry def : defines.entrySet())
            {
                arguments.add("-D" + def.getKey() + '=' + def.getValue());
            }
        }

        if (null != undefines)
        {
            for (String undef : undefines)
            {
                arguments.add("-U" + undef);
            }
        }

        if (null != includeDirs)
        {
            for (File inclusion : includeDirs)
            {
                arguments.add("-I" + inclusion.getPath());
            }
        }

        if ((null != backendArguments) && (backendArguments.length > 0))
        {
            option.setLength(0);
            option.append("-Wb");
            for (int nArg = 0; nArg < backendArguments.length; nArg++)
            {
                if (nArg > 0)
                {
                    option.append(',');
                }
                option.append(backendArguments[nArg]);
            }
            arguments.add(option.toString());
        }

        if (null != backendsPath)
        {
            arguments.add("-p" + backendsPath);
        }

        if (allowUnresolvedForward)
        {
            arguments.add("-nf");
        }

        if (allowCaseDifference)
        {
            arguments.add("-nc");
        }

        if (passCommentsAfter)
        {
            arguments.add("-k");
        }

        if (passCommentsBefore)
        {
            arguments.add("-K");
        }

        // Add the output directory
        arguments.add("-C" + outputDirectory);
        if (!outputDirectory.isDirectory())
        {
            outputDirectory.mkdirs();
        }

        // The C++ back end only accepts one source per invocation.  Call it
        // once for each source file.
        for (File source : inputs)
        {
            if (source.isFile())
            {
                compile(arguments, source);
            }
            else if (source.isDirectory())
            {
                for (File file : source.listFiles())
                {
                    compile(arguments, file);
                }
            }
            else
            {
                throw new MojoExecutionException(source.getPath() + " is not a file or directory");
            }
        }

        // Command is built.  Now try to run it.
    }

    /**
     * Slurp up the entire content of an InputStream into a String.
     *
     * @param inputStream
     * @return the stream's content.
     */
    private String gatherFromStream(InputStream inputStream)
    {
        StringBuilder result = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        while (true)
        {
            int next;
            try {
                next = reader.read();
            } catch (IOException ex) {
                getLog().error(ex.getMessage());
                break;
            }
            if (next < 0)
            {
                break;
            }
            result.append((char)next);
        }

        return result.toString();
    }

    /**
     * Compile one IDL source.
     *
     * @param arguments The compiler command with all options, less source path.
     * @param source The source path.
     * @throws MojoExecutionException
     */
    private void compile(List<String> arguments, File source)
            throws MojoExecutionException
    {
        // Apend the source path to the command
        String path = source.getPath();
        arguments.add(path);
        getLog().info("Compiling " + path);

        // Spawn the compiler in a subprocess
        try {
            ProcessBuilder pb = new ProcessBuilder(arguments);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exitValue = proc.waitFor();
            String output = gatherFromStream(proc.getInputStream());
            if (exitValue > 0)
            {
                throw new MojoExecutionException(
                        String.format("IDL compiler returned %d:  %s",
                        exitValue, output));
            }
            else
            {
                if (!output.isEmpty())
                {
                    getLog().info(output);
                }
            }
        } catch (IOException ex) {
            throw new MojoExecutionException("Error running IDL compiler:  ", ex);
        } catch (InterruptedException e) {
            throw new MojoExecutionException("Subprocess interrupted", e);
        }

        // Trim off the source path in case there will be more
        arguments.remove(arguments.size()-1);
    }
}
