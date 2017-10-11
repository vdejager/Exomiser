/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2017 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.autoconfigure.phenotype;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.monarchinitiative.exomiser.autoconfigure.DataDirectoryAutoConfiguration;
import org.monarchinitiative.exomiser.autoconfigure.UndefinedDataDirectoryException;
import org.monarchinitiative.exomiser.core.prioritisers.PriorityFactory;
import org.monarchinitiative.exomiser.core.prioritisers.util.DataMatrix;
import org.monarchinitiative.exomiser.core.prioritisers.util.DataMatrixIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author Jules Jacobsen <j.jacobsen@qmul.ac.uk>
 */
@Configuration
@ConditionalOnClass(PriorityFactory.class)
@ConditionalOnProperty("exomiser.phenotype.data-version")
@Import({DataDirectoryAutoConfiguration.class, PhenotypeMatchServiceAutoConfiguration.class})
@EnableConfigurationProperties(PhenotypeProperties.class)
@ComponentScan("org.monarchinitiative.exomiser.core.prioritisers")
public class PrioritiserAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PrioritiserAutoConfiguration.class);

    private final PhenotypeProperties phenotypeProperties;
    private final Path phenotypeDataDirectory;

    public PrioritiserAutoConfiguration(PhenotypeProperties phenotypeProperties, Path exomiserDataDirectory) {
        logger.info("Configuring prioritisers for version {}", phenotypeProperties.getDataVersion());
        this.phenotypeProperties = phenotypeProperties;
        this.phenotypeDataDirectory = determinePhenotypeDataDirectory(phenotypeProperties, exomiserDataDirectory);
    }

    private Path determinePhenotypeDataDirectory(PhenotypeProperties phenotypeProperties, Path exomiserDataDirectory) {
        Path originalPath = phenotypeProperties.getDataDirectory();
        if (originalPath == null) {
            logger.info("exomiser.phenotype.data-directory not defined - searching for candidate...");
            String version = phenotypeProperties.getDataVersion();
            if (version.isEmpty()) {
//                throw new IllegalArgumentException("exomiser.phenotype.data-version cannot be undefined");
                logger.info("Searching for phenotype data releases in {}", exomiserDataDirectory);
                //try searching for directories ending in "_phenotype", return the latest version based on the directory name
                List<Path> phenotypeDataDirectories = listPhenotypeDataDirectories(exomiserDataDirectory);
                if (phenotypeDataDirectories.isEmpty()) {
                    //otherwise return the root exomiser data directory - this could be a legacy setup.
                    logger.info("Unable to find any phenotype data releases.");
                }
                Path latestPhenotypeDataDir = phenotypeDataDirectories.stream().sorted(Comparator.reverseOrder())
                        .findFirst()
                        .orElse(exomiserDataDirectory);
                logger.info("Using phenotype data directory: {}", latestPhenotypeDataDir);
                return latestPhenotypeDataDir;

            } else {
                logger.info("Searching based on phenotype.data-version {}", version);
                String directoryName = String.format("%s_phenotype", version);
                Path automaticallyDefinedPath = exomiserDataDirectory.resolve(directoryName);
                logger.info("exomiser.phenotype.data-directory defined as: {}", automaticallyDefinedPath);
                return automaticallyDefinedPath;
            }
        }
        return originalPath;
    }

    List<Path> listPhenotypeDataDirectories(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (entry.toString().endsWith("_phenotype")) {
                    logger.info("Found phenotype data directory {}", entry);
                    result.add(entry);
                }
            }
        } catch (DirectoryIteratorException | IOException ex) {
            // I/O error encounted during the iteration, the cause is an IOException
            throw new UndefinedDataDirectoryException("Unable to read/find valid phenotype data directory");
        }
        return result;
    }


    @Bean
    public Path phenotypeDataDirectory() {
        return phenotypeDataDirectory;
    }

    @Bean
    @ConditionalOnMissingBean(name = "phenixDataDir")
    public Path phenixDataDirectory() {
        String phenixDataDirValue = phenotypeProperties.getPhenixDataDir();
        Path phenixDataDirectory = phenotypeDataDirectory().resolve(phenixDataDirValue);
        logger.debug("phenixDataDirectory: {}", phenixDataDirectory.toAbsolutePath());
        return phenixDataDirectory;
    }

    @Bean
    @ConditionalOnMissingBean(name = "hpoOboFilePath")
    public Path hpoOboFilePath() {
        String hpoFileName = phenotypeProperties.getHpoFileName();
        Path hpoFilePath = phenixDataDirectory().resolve(hpoFileName);
        logger.debug("hpoOboFilePath: {}", hpoFilePath.toAbsolutePath());
        return hpoFilePath;
    }

    @Bean
    @ConditionalOnMissingBean(name = "hpoAnnotationFilePath")
    public Path hpoAnnotationFilePath() {
        String hpoAnnotationFileValue = phenotypeProperties.getHpoAnnotationFile();
        Path hpoAnnotationFilePath = phenixDataDirectory().resolve(hpoAnnotationFileValue);
        logger.debug("hpoAnnotationFilePath: {}", hpoAnnotationFilePath.toAbsolutePath());
        return hpoAnnotationFilePath;
    }

    /**
     * This needs a lot of RAM and is slow to create from the randomWalkFile, so
     * it's set as lazy use on the command-line.
     *
     * @return
     */
    @Lazy
    @Bean
    @ConditionalOnMissingBean(name = "randomWalkMatrix")
    public DataMatrix randomWalkMatrix() {
        String randomWalkFileNameValue = phenotypeProperties.getRandomWalkFileName();
        Path randomWalkFilePath = phenotypeDataDirectory().resolve(randomWalkFileNameValue);

        String randomWalkIndexFileNameValue = phenotypeProperties.getRandomWalkIndexFileName();
        Path randomWalkIndexFilePath = phenotypeDataDirectory().resolve(randomWalkIndexFileNameValue);

        return DataMatrixIO.loadDataMatrix(randomWalkFilePath.toString(), randomWalkIndexFilePath.toString(), true);
    }

    @Bean
    @ConditionalOnMissingBean(name = "phenotypeDataSource")
    public DataSource phenotypeDataSource() {
        return new HikariDataSource(phenotypeDataSourceConfig());
    }

    public HikariConfig phenotypeDataSourceConfig() {
        String version = phenotypeProperties.getDataVersion();
        //omit the .h2.db extensions
        String dbFileName = String.format("%s_exomiser_phenotype", version);

        Path dbPath = phenotypeDataDirectory().resolve(dbFileName);

        String startUpArgs = ";MODE=PostgreSQL;SCHEMA=EXOMISER;DATABASE_TO_UPPER=FALSE;IFEXISTS=TRUE;AUTO_RECONNECT=TRUE;ACCESS_MODE_DATA=r;";

        String jdbcUrl = String.format("jdbc:h2:file:%s%s", dbPath.toAbsolutePath(), startUpArgs);

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.h2.Driver");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(3);
        config.setPoolName(String.format("exomiser-phenotype-%s", version));
        logger.info("Set up {} pool {} connections from {}", config.getPoolName(), config.getMaximumPoolSize(), config.getJdbcUrl());
        return config;
    }
}
