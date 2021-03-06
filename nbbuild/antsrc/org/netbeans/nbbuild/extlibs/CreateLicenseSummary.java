/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.nbbuild.extlibs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Resource;
import org.apache.tools.ant.types.selectors.SelectorUtils;
import org.netbeans.nbbuild.JUnitReportWriter;
import org.netbeans.nbbuild.extlibs.licenseinfo.Fileset;
import org.netbeans.nbbuild.extlibs.licenseinfo.Licenseinfo;

/**
 * Creates a list of external binaries and their licenses.
 */
public class CreateLicenseSummary extends Task {

    private File nball;

    public void setNball(File nball) {
        this.nball = nball;
    }

    private File build;

    public void setBuild(File build) {
        this.build = build;
    }

    private File licenseStub;

    public void setLicenseStub(File licenseStub) {
        this.licenseStub = licenseStub;
    }

    private File license;

    public void setLicense(File license) {
        this.license = license;
    }

    private File notice;

    public void setNotice(File notice) {
        this.notice = notice;
    }

    private File noticeStub;

    public void setNoticeStub(File noticeStub) {
        this.noticeStub = noticeStub;
    }

    private File reportFile;

    public void setReport(File report) {
        this.reportFile = report;
    }
    
    private File licenseTargetDir = null;
    
    public void setLicenseTargetDir(File licenseTargetDir) {
        this.licenseTargetDir = licenseTargetDir;
    }

    private Set<String> modules;
    public void setModules(String modules) {
        if(modules == null) {
            modules = "";
        }
        this.modules = new TreeSet<>();
        this.modules.addAll(Arrays.asList(modules.split("[, ]+")));
    }
    
    private boolean binary;
    public void setBinary(boolean binary) {
        this.binary = binary;
    }
    
    private FileSet moduleFiles;
    public FileSet createModuleFiles() {
        return (moduleFiles = new FileSet());
    }

    private Map<String, String> pseudoTests;

    public @Override
    void execute() throws BuildException {
        if (modules == null || modules.isEmpty()) {
            modules = new TreeSet<>();
            for (String cluster : getProject().getProperty("nb.clusters.list").split("[, ]+")) {
                modules.addAll(Arrays.asList(getProject().getProperty(cluster).split("[, ]+")));
            }
            modules.add("nbbuild");
        }
        
        pseudoTests = new LinkedHashMap<>();
        
        try (PrintWriter licenseWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(license), "UTF-8"));
                PrintWriter noticeWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(notice), "UTF-8"))) {
            
            try (Reader r = new InputStreamReader(new FileInputStream(licenseStub), "UTF-8")) {
                int read;
                while ((read = r.read()) != (-1)) {
                    licenseWriter.write(read);
                }
            }

            try (Reader r = new InputStreamReader(new FileInputStream(noticeStub), "UTF-8")) {
                int read;
                while ((read = r.read()) != (-1)) {
                    noticeWriter.write(read);
                }
            }

            Set<String> notices = new HashSet<>();
            Set<String> licenseNames = new TreeSet<>();

            if(binary) {
                evaluateBinaries(licenseWriter, noticeWriter, notices, licenseNames);
            }
            evaluateLicenseInfo(licenseWriter, noticeWriter, notices, licenseNames);

            File licenses = new File(new File(nball, "nbbuild"), "licenses");
            if(licenseTargetDir != null) {
                licenseTargetDir.mkdirs();
            }
            for (String licenseName : licenseNames) {
                if (licenseName == null) {
                    continue;
                }
                File license = new File(licenses, licenseName);
                if (!license.isFile()) {
                    continue;
                }
                if (licenseTargetDir == null) {
                    licenseWriter.println();
                    licenseWriter.println();
                    licenseWriter.println("===");
                    licenseWriter.println("======");
                    licenseWriter.println("========================= " + licenseName + " =========================");
                    licenseWriter.println();
                    try (InputStream is = new FileInputStream(license)) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            licenseWriter.println(line);
                        }
                        r.close();
                    }
                } else {
                    File targetFile = new File(licenseTargetDir, licenseName);
                    Files.copy(license.toPath(), targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }

            }

            licenseWriter.flush();
        } catch (IOException x) {
            throw new BuildException(x, getLocation());
        }
        log(license + ": written");
        JUnitReportWriter.writeReport(this, null, reportFile, pseudoTests);
    }
    
    private void evaluateLicenseInfo(final PrintWriter licenseWriter, final PrintWriter noticeWriter, Set<String> notices, Set<String> licenseNames) throws IOException {
        List<String> footnotes = new ArrayList<>();
        boolean headerPrinted = false;
        
        for(String module : modules) {
            File moduleDir = new File(nball, module);
            File licenseInfoFile = new File(moduleDir, "licenseinfo.xml");
            if(! licenseInfoFile.exists()) {
                continue;
            }
            
            Licenseinfo licenseInfo = Licenseinfo.parse(licenseInfoFile);
            
            for(Fileset fs: licenseInfo.getFilesets()) {
                if("Apache-2.0-ASF".equals(fs.getLicenseRef())) {
                    continue;
                }
                if(binary && fs.isSourceOnly()) {
                    continue;
                }

                if (!headerPrinted) {
                    licenseWriter.println();
                    licenseWriter.println("******************************************************************************************************************************************************");
                    licenseWriter.println("Apache NetBeans includes a number of source files that are not covered by the apache license. The following files are part of this distribution.");
                    licenseWriter.println("******************************************************************************************************************************************************");
                    licenseWriter.println();

                    licenseWriter.printf("%-100s%40s%10s\n", "Sourcefile", "LICENSE", "NOTES");
                    if(licenseTargetDir != null) {
                        licenseWriter.printf("%-100s%40s\n", "(path in the source)", "(text is in file in licenses directory)");
                    } else {
                        licenseWriter.printf("%-100s%40s\n", "(path in the source)", "(see license text reproduced below)");
                    }
                    licenseWriter.println("------------------------------------------------------------------------------------------------------------------------------------------------------");
                    headerPrinted = true;
                }

                String notes = "";
                if(fs.getLicenseInfo() != null) {
                    int idx = footnotes.indexOf(fs.getLicenseInfo());
                    if(idx < 0) {
                        footnotes.add(fs.getLicenseInfo());
                        idx = footnotes.size() - 1;
                    }
                    notes = Integer.toString(idx + 1);
                }
                for(File f: fs.getFiles()) {
                    Path relativePath = nball.toPath().relativize(f.toPath());
                    licenseWriter.printf("%-120s%20s%10s\n", relativePath, fs.getLicenseRef(), notes);
                }
                
                if(fs.getLicenseRef() != null) {
                    licenseNames.add(fs.getLicenseRef());
                }
                
                String notice = fs.getNotice();
                if (notice != null) {
                    notice = notice.trim();
                    if (!notices.contains(notice)) {
                        notices.add(notice);
                        addNotice(noticeWriter, notice);
                    }
                }
            }
        }
        
        if (!footnotes.isEmpty()) {
            licenseWriter.print("\n");
            licenseWriter.print("Notes\n");
            licenseWriter.print("-----\n");
            for (int i = 0; i < footnotes.size(); i++) {
                String footnote = footnotes.get(i);
                licenseWriter.printf("[%3d] %s\n\n", i + 1, footnote.trim());
            }
        }
    }

    private void evaluateBinaries(final PrintWriter licenseWriter, final PrintWriter noticeWriter, Set<String> notices, Set<String> licenseNames) throws IOException {
        Map<Long, Map<String, String>> crc2License = findCrc2LicenseHeaderMapping();
        Map<String, Map<String, String>> binaries2LicenseHeaders = new TreeMap<>();
        StringBuilder testBinariesAreUnique = new StringBuilder();
        List<String> ignoredPatterns = VerifyLibsAndLicenses.loadPatterns("ignored-binary-overlaps");
        if (build != null)
            findBinaries(build, binaries2LicenseHeaders, crc2License, new HashMap<>(), "", testBinariesAreUnique, ignoredPatterns);
        if (moduleFiles != null) {
            for (Resource r : moduleFiles) {
                try (InputStream is = r.getInputStream()) {
                    long crc = computeCRC32(is);
                    Map<String, String> headers = crc2License.get(crc);
                    if (headers != null) {
                        binaries2LicenseHeaders.put(r.getName(), headers);
                    }
                }
            }
        }
        if (binaries2LicenseHeaders.isEmpty())
            return ;
        pseudoTests.put("testBinariesAreUnique", testBinariesAreUnique.length() > 0 ? "Some binaries are duplicated (edit nbbuild/antsrc/org/netbeans/nbbuild/extlibs/ignored-binary-overlaps as needed)" + testBinariesAreUnique : null);
        
        licenseWriter.println();
        licenseWriter.println("********************************************************************************");
        licenseWriter.println("Apache NetBeans includes a number of components and libraries with separate");
        licenseWriter.println("copyright notices and license terms. Your use of those components are");
        licenseWriter.println("subject to the terms and conditions of the following licenses. ");
        licenseWriter.println("********************************************************************************");
        licenseWriter.println();
        
        licenseWriter.printf("%-68s%12s\n", "THIRD-PARTY COMPONENT FILE", "LICENSE");
        if(licenseTargetDir != null) {
            licenseWriter.printf("%-40s%40s\n", "(path in the installation)", "(text is in file in licenses directory)");
        } else {
            licenseWriter.printf("%-40s%40s\n", "(path in the installation)", "(see license text reproduced below)");
        }
        licenseWriter.println("--------------------------------------------------------------------------------");
        
        for (Map.Entry<String, Map<String, String>> entry : binaries2LicenseHeaders.entrySet()) {
            String binary = entry.getKey();
            Map<String, String> headers = entry.getValue();
            licenseWriter.printf("%-69s %s\n", binary, getMaybeMissing(headers, "License"));
            String license = headers.get("License");
            if (license != null) {
                licenseNames.add(license);
            } else {
                //TODO: should be error/test failure, or something like that.
                System.err.println("No license for: " + binary);
            }
            
            String notice = headers.get("notice");
            if (notice != null) {
                notice = notice.trim();
                if (!notices.contains(notice)) {
                    notices.add(notice);
                    addNotice(noticeWriter, notice);
                }
            }
            
        }
//                String[] otherHeaders = {"Name", "Version", "Description", "Origin"};
//                Map<Map<String,String>,Set<String>> licenseHeaders2Binaries = new LinkedHashMap<Map<String,String>,Set<String>>();
//                for (Map.Entry<String,Map<String,String>> entry : binaries2LicenseHeaders.entrySet()) {
//                    Map<String,String> headers = new HashMap<String,String>(entry.getValue());
//                    headers.keySet().retainAll(Arrays.asList(otherHeaders));
//                    Set<String> binaries = licenseHeaders2Binaries.get(headers);
//                    if (binaries == null) {
//                        binaries = new TreeSet<String>();
//                        licenseHeaders2Binaries.put(headers, binaries);
//                    }
//                    binaries.add(entry.getKey());
//                }
//                for (Map.Entry<Map<String,String>,Set<String>> entry : licenseHeaders2Binaries.entrySet()) {
//                    pw.println();
//                    for (String header : otherHeaders) {
//                        pw.printf("%s: %s\n", header, getMaybeMissing(entry.getKey(), header));
//                    }
//                    pw.println ("Files:");
//                    for (String binary : entry.getValue()) {
//                        pw.println(binary);
//                    }
//                }
    }

    private String getMaybeMissing(Map<String, String> headers, String headerName) {
        if (headers.containsKey(headerName)) {
            return headers.get(headerName);
        } else {
            return "<unknown>";
        }
    }

    private Map<Long, Map<String, String>> findCrc2LicenseHeaderMapping() throws IOException {
        Map<Long, Map<String, String>> crc2LicenseHeaders = new HashMap<>();
        for (String cluster : getProject().getProperty("nb.clusters.list").split("[, ]+")) {
            for (String module : getProject().getProperty(cluster).split("[, ]+")) {
                File d = new File(new File(nball, module), "external");
                Set<String> hgFiles = VerifyLibsAndLicenses.findHgControlledFiles(d);
                Map<String, Map<String, String>> binary2License = findBinary2LicenseHeaderMapping(hgFiles, d);
                for (String n : hgFiles) {
                    if (!n.endsWith(".jar") && !n.endsWith(".zip") && !n.endsWith(".xml")
                            && !n.endsWith(".js") && !n.endsWith(".dylib")) {
                        continue;
                    }
                    Map<String, String> headers = binary2License.get(n);
                    if (headers == null) {
                        continue;
                    }
                    File f = new File(d, n);
                    InputStream is = new FileInputStream(f);
                    try {
                        crc2LicenseHeaders.put(computeCRC32(is), headers);
                    } finally {
                        is.close();
                    }
                    if (!n.endsWith(".jar") && !n.endsWith(".zip")) {
                        continue;
                    }
                    ZipFile zf = new ZipFile(f);
                    try {
                        Enumeration<? extends ZipEntry> entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            String innerName = entry.getName();
                            if (!innerName.endsWith(".jar") && !innerName.endsWith(".zip")) {
                                continue;
                            }
                            Map<String, String> nestedHeaders = binary2License.get(n + "!/" + innerName);
                            if (nestedHeaders == null) nestedHeaders = headers;
                            is = zf.getInputStream(entry);
                            try {
                                crc2LicenseHeaders.put(computeCRC32(is), nestedHeaders);
                            } finally {
                                is.close();
                            }
                        }
                    } finally {
                        zf.close();
                    }
                }
            }
        }
        return crc2LicenseHeaders;
    }

    private void addNotice(PrintWriter output, String notice) throws IOException {
        String[] lines = notice.split("\n");
        boolean previousLineEmpty = true;
        int n = lines.length;
        for (int i = 0; i < n; i++) {
            String line = lines[i];
            line = line.trim();
            boolean empty = line.length() == 0;
            if (empty && previousLineEmpty) {
                // Skip line
            } else {
                previousLineEmpty = empty;
                if (!empty && i < n - 1 && line.startsWith("This product includes software") && lines[i + 1].startsWith("The Apache Software Foundation")) {
                    i += 2;
                    previousLineEmpty = false;
                    // Skip
                } else {
                    output.println(line);
                }
            }
        }
    }

    private long computeCRC32(InputStream is) throws IOException {
        byte[] buf = new byte[4096];
        CRC32 crc32 = new CRC32();
        int read;
        while ((read = is.read(buf)) != -1) {
            crc32.update(buf, 0, read);
        }
        return crc32.getValue();
    }

    static Map<String, Map<String, String>> findBinary2LicenseHeaderMapping(Set<String> cvsFiles, File d) throws IOException {
        Map<String, Map<String, String>> binary2LicenseHeaders = new HashMap<>();
        for (String n : cvsFiles) {
            if (!n.endsWith("-license.txt")) {
                continue;
            }
            Map<String, String> headers = new HashMap<>();
            try (InputStream is = new FileInputStream(new File(d, n))) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                while ((line = r.readLine()) != null && line.length() > 0) {
                    Matcher m = Pattern.compile("([a-zA-Z]+): (.+)").matcher(line);
                    if (m.matches()) {
                        headers.put(m.group(1), m.group(2));
                    }
                }
                r.close();
            }
            String files = headers.remove("Files");
            if (files != null) {
                for (String file : files.split("[, ]+")) {
                    binary2LicenseHeaders.put(file, headers);
                }
            } else {
                binary2LicenseHeaders.put(n.replaceFirst("-license\\.txt$", ".jar"), headers);
                binary2LicenseHeaders.put(n.replaceFirst("-license\\.txt$", ".zip"), headers);
                binary2LicenseHeaders.put(n.replaceFirst("-license\\.txt$", ".xml"), headers);
                binary2LicenseHeaders.put(n.replaceFirst("-license\\.txt$", ".js"), headers);
                binary2LicenseHeaders.put(n.replaceFirst("-license\\.txt$", ".dylib"), headers);
            }
            File notice = new File(d, n.replace("-license.txt", "-notice.txt"));
            if (notice.canRead()) {
                StringBuilder noticeText = new StringBuilder();
                try (Reader r = new InputStreamReader(new FileInputStream(notice), "UTF-8")) {
                    int read;
                    while ((read = r.read()) != (-1)) {
                        noticeText.append((char) read);
                    }
                }
                headers.put("notice", noticeText.toString());
            }
        }
        return binary2LicenseHeaders;
    }

    private void findBinaries(File d, Map<String, Map<String, String>> binaries2LicenseHeaders, Map<Long, Map<String, String>> crc2LicenseHeaders,
            Map<Long, String> crc2Binary, String prefix, StringBuilder testBinariesAreUnique, List<String> ignoredPatterns) throws IOException {
        if (prefix.length() > 1000) {
            log("#170823: possible loop in " + prefix, Project.MSG_WARN);
        }
        String[] kids = d.list();
        if (kids == null) {
            throw new IOException("Could not list " + d);
        }
        Arrays.sort(kids);
        for (String n : kids) {
            File f = new File(d, n);
            if (f.isDirectory()) {
                findBinaries(f, binaries2LicenseHeaders, crc2LicenseHeaders, crc2Binary, prefix + n + "/", testBinariesAreUnique, ignoredPatterns);
            } else if (n.endsWith(".jar") || n.endsWith(".zip") || n.endsWith(".xml") || n.endsWith(".js") || n.endsWith(".dylib")) {
                try (InputStream is = new FileInputStream(f)) {
                    long crc = computeCRC32(is);
                    Map<String, String> headers = crc2LicenseHeaders.get(crc);
                    if (headers != null) {
                        String path = prefix + n;
                        binaries2LicenseHeaders.put(path, headers);
                        String otherPath = crc2Binary.put(crc, path);
                        if (otherPath != null) {
                            boolean ignored = false;
                            for (String pattern : ignoredPatterns) {
                                String[] parts = pattern.split(" ");
                                assert parts.length == 2 : pattern;
                                if (SelectorUtils.matchPath(parts[0], otherPath) && SelectorUtils.matchPath(parts[1], path)) {
                                    ignored = true;
                                    break;
                                }
                                if (SelectorUtils.matchPath(parts[0], path) && SelectorUtils.matchPath(parts[1], otherPath)) {
                                    ignored = true;
                                    break;
                                }
                            }
                            if (!ignored) {
                                testBinariesAreUnique.append('\n').append(otherPath).append(" and ").append(path).append(" are identical");
                            }
                        }
                    }
                }
            }
        }
    }

}
