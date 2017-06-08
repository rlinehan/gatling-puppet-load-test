import groovy.json.JsonOutput

// NOTE: would really like to start writing some classes/enums for some of this stuff,
// and importing them here, but it doesn't seem like support for that is really
// viable yet.  See https://issues.jenkins-ci.org/browse/JENKINS-37125 and
// https://issues.jenkins-ci.org/browse/JENKINS-31155 .

def get_filename(path) {
    // This is a bummer, but all of the JVM built-ins for manipulating file paths
    // appear to be blacklisted in their groovy security sandbox thingy, so rather
    // than trying to figure out how to puppetize changes to the whitelist, we're
    // just rolling our own for now.
    return path.substring(path.lastIndexOf("/") + 1,
            path.length())
}

def get_pe_server_era(pe_version, find_latest) {
    // A normal groovy switch/case statement with regex matchers doesn't seem
    // to work in Jenkins: https://issues.jenkins-ci.org/browse/JENKINS-37214
    if (pe_version ==~ /^3\.[78]\..*/) {
        return [type: "pe",
                service_name: "pe-puppetserver",
                version: pe_version,
                find_latest: find_latest,
                tk_auth: false,
                puppet_bin_dir: "/opt/puppet/bin",
                r10k_version: "1.5.1",
                file_sync_available: false,
                file_sync_enabled: false,
                node_classifier: true,
                facter_structured_facts: false]
    } else if (pe_version ==~ /^3\..*/) {
        return [type: "pe",
                service_name: "pe-httpd",
                version: pe_version,
                find_latest: find_latest,
                tk_auth     : false,
                puppet_bin_dir: "/opt/puppet/bin",
                r10k_version: "1.5.1",
                file_sync_available: false,
                file_sync_enabled: false,
                node_classifier: false,
                facter_structured_facts: false]
    } else if (pe_version ==~ /^201[67]\..*/) {
        return [type: "pe",
                service_name: "pe-puppetserver",
                version: pe_version,
                find_latest: find_latest,
                tk_auth     : true,
                puppet_bin_dir: "/opt/puppetlabs/puppet/bin",
                r10k_version: "2.3.0",
                file_sync_available: true,
                file_sync_enabled: false,
                node_classifier: true,
                facter_structured_facts: true]
    } else if (pe_version ==~ /^2015\.3\..*/) {
        return [type: "pe",
                service_name: "pe-puppetserver",
                version: pe_version,
                find_latest: find_latest,
                tk_auth     : true,
                puppet_bin_dir: "/opt/puppetlabs/puppet/bin",
                r10k_version: "2.3.0",
                file_sync_available: true,
                file_sync_enabled: true,
                node_classifier: true,
                facter_structured_facts: true]
    } else if (pe_version ==~ /^2015\..*/) {
        return [type: "pe",
                service_name: "pe-puppetserver",
                version: pe_version,
                find_latest: find_latest,
                tk_auth     : false,
                puppet_bin_dir: "/opt/puppetlabs/puppet/bin",
                r10k_version: "2.3.0",
                file_sync_available: false,
                file_sync_enabled: false,
                node_classifier: true,
                facter_structured_facts: true]
    } else {
        error "Unrecognized PE version: '${pe_version}'"
    }
}

def get_oss_server_era(oss_version) {
    // TODO: eventually we will probably want to do something more sophisticated
    //  here; currently only support 'latest'/'master'/'stable' OSS puppetserver,
    //  and 'latest' agent
    switch (oss_version) {
        case ["latest", "master"]:
            return [type: "oss",
                    service_name: "puppetserver",
                    version: oss_version,
                    // HERE
                    agent_version: "latest",
                    tk_auth: true,
                    puppet_bin_dir: "/opt/puppetlabs/puppet/bin",
                    r10k_version: "2.3.0",
                    file_sync_available: false,
                    file_sync_enabled: false,
                    node_classifier: false,
                    facter_structured_facts: true]
        case "stable":
            return [type: "oss",
                    service_name: "puppetserver",
                    version: oss_version,
                    agent_version: "latest",
                    tk_auth: false,
                    puppet_bin_dir: "/opt/puppetlabs/puppet/bin",
                    r10k_version: "2.3.0",
                    file_sync_available: false,
                    file_sync_enabled: false,
                    node_classifier: false,
                    facter_structured_facts: true]
        default:
            error "Unrecognized OSS version: '${oss_version}'"
    }
}

def get_server_era(server_version) {
    if (server_version["type"] == "pe") {
        return get_pe_server_era(server_version["pe_version"], server_version["find_latest"])
    } else if (server_version["type"] == "oss") {
        return get_oss_server_era(server_version["version"])
    } else {
        error "Unsupported server type: ${server_version["type"]}"
    }
}


def step000_provision_sut(SKIP_PROVISIONING, script_dir) {
    echo "SKIP PROVISIONING?: ${SKIP_PROVISIONING} (${SKIP_PROVISIONING.class})"
    if (!SKIP_PROVISIONING) {
        withEnv(["SUT_HOST=${SUT_HOST}"]) {
            sh "${script_dir}/000_provision_sut.sh"
        }
    }
}

def step010_setup_beaker(script_dir, server_version) {
    if (server_version["type"] == "pe") {
        if (server_version["find_latest"] == true) {
            withEnv(["SUT_HOST=${SUT_HOST}",
                     "pe_dir=http://enterprise.delivery.puppetlabs.net/${server_version["pe_version"]}/ci-ready/",
                     "pe_family=${server_version["pe_version"]}"]) {
                sh "${script_dir}/010_setup_beaker.sh"
            }
        } else {
            withEnv(["SUT_HOST=${SUT_HOST}",
                     "pe_version=${server_version["pe_version"]}",
                     "pe_family=${server_version["pe_version"]}"]) {
                sh "${script_dir}/010_setup_beaker.sh"
            }
        }
    } else if (server_version["type"] == "oss") {
        withEnv(["SUT_HOST=${SUT_HOST}"]) {
            sh "${script_dir}/010_setup_beaker.sh"
        }

    } else {
        error "Unsupported server type: ${server_version["type"]}"
    }
}

def step020_install_server(SKIP_SERVER_INSTALL, script_dir, server_era) {
    echo "SKIP SERVER INSTALL?: ${SKIP_SERVER_INSTALL} (${SKIP_SERVER_INSTALL.class})"
    if (SKIP_SERVER_INSTALL) {
        echo "Skipping server install because SKIP_SERVER_INSTALL is set."
    } else {
        if (server_era["type"] == "pe") {
            withEnv(["PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}",
                     "PUPPET_SERVER_TK_AUTH=${server_era["tk_auth"]}"]) {
                sh "${script_dir}/020_install_pe.sh"
            }
        } else if (server_era["type"] == "oss") {
            withEnv(["PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}",
                     "PUPPET_SERVER_TK_AUTH=${server_era["tk_auth"]}",
                     "PACKAGE_BUILD_VERSION=${server_era["version"]}",
                     "PUPPET_AGENT_VERSION=${server_era["agent_version"]}"]) {
                sh "${script_dir}/020_install_oss.sh"
            }
        } else {
            error "Unsupported server type: ${server_era["type"]}"
        }

        sh "${script_dir}/021_install_common.sh"
    }
}

def step025_collect_facter_data(job_name, gatling_simulation_config, script_dir, server_era) {
    withEnv(["PUPPET_GATLING_SIMULATION_CONFIG=${gatling_simulation_config}",
             "PUPPET_GATLING_SIMULATION_ID=${job_name}",
             "FACTER_STRUCTURED_FACTS=${server_era["facter_structured_facts"]}"]) {
        sh "${script_dir}/025_collect_facter_data.sh"
    }
}

def step040_install_puppet_code(script_dir, code_deploy, server_era) {
    switch (code_deploy["type"]) {
        case "r10k":
            withEnv(["PUPPET_GATLING_R10K_CONTROL_REPO=${code_deploy["control_repo"]}",
                     "PUPPET_GATLING_R10K_BASEDIR=${code_deploy["basedir"]}",
                     "PUPPET_GATLING_R10K_ENVIRONMENTS=${code_deploy["environments"].join(",")}",
                     "PUPPET_BIN_DIR=${server_era["puppet_bin_dir"]}",
                     "PUPPET_R10K_VERSION=${server_era["r10k_version"]}"
                    ]) {
                sh "${script_dir}/040_install_puppet_code-r10k.sh"
            }
            break
        case "ops":
            sh "${script_dir}/040_install_puppet_code-ops_tarball.sh"
            break
        default:
            error "Unsupported code type: ${code_deploy["type"]}"
            break
    }
}

def step045_install_hiera_config(script_dir, code_deploy, server_era) {
    hiera_config_source_file = (code_deploy["hiera_config_source_file"] == null) ?
            "" : code_deploy["hiera_config_source_file"]
    hiera_config_datadir = (code_deploy["hiera_config_datadir"] == null) ?
            "" : code_deploy["hiera_config_datadir"]
    if ((hiera_config_source_file == "") && (hiera_config_datadir == "")) {
        echo "Skipping hiera configuration because no options specified in job"
    } else {
        withEnv(["PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}",
                 "PUPPET_GATLING_HIERA_CONFIG_SOURCE_FILE=${hiera_config_source_file}",
                 "PUPPET_GATLING_HIERA_CONFIG_DATADIR=${hiera_config_datadir}"
        ]) {
            sh "${script_dir}/045_install_hiera_config.sh"
        }
    }
}

def step050_file_sync(script_dir, server_era) {
    if (server_era["file_sync_available"] == true) {
        if (server_era["file_sync_enabled"] == false) {
            echo "Server supports file sync, but it is not enabled.  Enabling."
            sh "${script_dir}/050_enable_file_sync.sh"
        }
        sh "${script_dir}/055_perform_file_sync.sh"
    } else {
        echo "Server does not support file sync, skipping."
    }
}

def step060_classify_nodes(script_dir, gatling_simulation_config, server_era) {
    withEnv(["PUPPET_GATLING_SIMULATION_CONFIG=${gatling_simulation_config}",
             "PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}"]) {
        if (server_era["node_classifier"] == true) {
            sh "${script_dir}/060_classify_nodes-NC-API.sh"
        } else {
            echo "Node classifier not available, modifying site.pp"
            sh "${script_dir}/060_classify_nodes-site-pp.sh"
        }
    }
}

def step070_validate_classification() {
    echo "Hi! TODO: I should be validating classification on your SUT, but I'm not."
}

def step075_customize_puppet_settings(script_dir, puppet_settings) {
    if (puppet_settings == null) {
        echo "Skipping settings customization; no overrides found in `puppet_settings`."
    } else {
        puppet_settings_json = JsonOutput.toJson(puppet_settings)
        withEnv(["PUPPET_GATLING_PUPPET_SETTINGS=${puppet_settings_json}",
                 "PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}"]) {
            sh "${script_dir}/075_customize_puppet_settings.sh"
        }
    }
}

def step080_customize_java_args(script_dir, server_java_args, server_era) {
    if ((server_java_args == null) || (server_java_args == "")) {
        echo "Skipping java_args configuration because none specified in job"
    } else {
        withEnv(["PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}",
                 "PUPPET_SERVER_JAVA_ARGS=${server_java_args}"
        ]) {
            sh "${script_dir}/080_configure_java_args.sh"
        }
    }
}

def step081_customize_jruby_jar(script_dir, jruby_jar, server_era) {
    // Setting the jruby jar to an empty string will effectively cause the
    // beaker script to tell puppetserver to use the default jar path
    def jar_string = jruby_jar ?: ""

    withEnv(["PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}",
             "PUPPET_GATLING_JRUBY_JAR=${jar_string}"
    ]) {
        sh "${script_dir}/081_configure_jruby_jar.sh"
    }
}

def step085_customize_hocon_settings(script_dir, settings, server_era) {
    if (settings == null) {
        echo "Skipping hocon settings customization; no overrides found in `hocon_settings`."
    } else {
        settings_json = JsonOutput.toJson(settings)
        withEnv(["PUPPET_GATLING_HOCON_SETTINGS=${settings_json}",
                 "PUPPET_SERVER_SERVICE_NAME=${server_era["service_name"]}"]) {
            sh "${script_dir}/085_customize_hocon_settings.sh"
        }
    }
}

def step090_launch_bg_scripts(script_dir, background_scripts) {
    if (background_scripts == null) {
        echo "No background scripts configured, skipping."
    } else {
        withEnv(["SUT_BACKGROUND_SCRIPTS=${background_scripts.join("\n")}"]) {
            sh "${script_dir}/090_start_bg_scripts.sh"
        }
    }
}

def step100_run_gatling_sim(job_name, gatling_simulation_config, script_dir) {
    withEnv(["PUPPET_GATLING_SIMULATION_CONFIG=${gatling_simulation_config}",
             "PUPPET_GATLING_SIMULATION_ID=${job_name}",
             "SUT_HOST=${SUT_HOST}"]) {
        sh "${script_dir}/100_run_simulation.sh"
    }
}

def step105_stop_bg_scripts(script_dir, background_scripts) {
    if (background_scripts == null) {
        echo "No background scripts configured, skipping."
    } else {
        sh "${script_dir}/105_stop_bg_scripts.sh"
    }
}

def step110_collect_sut_artifacts(script_dir, job_name, archive_sut_files) {
    if (archive_sut_files == null) {
        echo "No SUT archive files configured, skipping."
    } else {
        echo "Collecting SUT archive files for job '${job_name}'"
        withEnv(["SUT_ARCHIVE_FILES=${archive_sut_files.join("\n")}",
                 "PUPPET_GATLING_SIMULATION_ID=${job_name}"]) {
            sh "${script_dir}/110_archive_sut_files.sh"
        }
        for (f in archive_sut_files) {
            String filename = get_filename(f);
            String filePath = "puppet-gatling/${job_name}/sut_archive_files/${filename}"
            echo "Archiving SUT file: '${filePath}'"
            sh "if [ ! -f './${filePath}' ] ; then echo 'ERROR! FILE DOES NOT EXIST!'; false ; fi"
            archive "${filePath}"
        }
    }
}

def step900_collect_driver_artifacts() {
    // NOTE: this DSL step requires the puppet-gatling-jenkins plugin.  It also
    // depends on some data that gets created via 025_collect_facter_data.sh
    puppetGatlingArchive()
}

SCRIPT_DIR = "./jenkins-integration/jenkins-jobs/common/scripts/job-steps"

def single_pipeline(job) {
    node {
        checkout scm

        SKIP_SERVER_INSTALL = (SKIP_SERVER_INSTALL == "true")
        SKIP_PROVISIONING = (SKIP_PROVISIONING == "true")

        job_name = job['job_name']

        stage '000-provision-sut'
        step000_provision_sut(SKIP_PROVISIONING, SCRIPT_DIR)

        stage '010-setup-beaker'
        step010_setup_beaker(SCRIPT_DIR, job["server_version"])

        server_era = get_server_era(job["server_version"])

        stage '020-install-server'
        step020_install_server(SKIP_SERVER_INSTALL, SCRIPT_DIR, server_era)

        stage '025-collect-facter-data'
        step025_collect_facter_data(job_name,
                job['gatling_simulation_config'],
                SCRIPT_DIR,
                server_era)

        stage '040-install-puppet-code'
        step040_install_puppet_code(SCRIPT_DIR, job["code_deploy"], server_era)

        stage '045-install-hiera-config'
        step045_install_hiera_config(SCRIPT_DIR, job["code_deploy"], server_era)

        stage '050-file-sync'
        step050_file_sync(SCRIPT_DIR, server_era)

        stage '060-classify-nodes'
        step060_classify_nodes(SCRIPT_DIR,
                job["gatling_simulation_config"],
                server_era)

        stage '070-validate-classification'
        step070_validate_classification()

        stage '075-customize-puppet-settings'
        step075_customize_puppet_settings(SCRIPT_DIR, job['puppet_settings'])

        stage '080-customize-java-args'
        step080_customize_java_args(SCRIPT_DIR, job["server_java_args"], server_era)

        stage '081-customize-jruby-jar'
        step081_customize_jruby_jar(SCRIPT_DIR, job["jruby_jar"], server_era)

        stage '085-customize-hocon-settings'
        step085_customize_hocon_settings(SCRIPT_DIR, job['hocon_settings'], server_era)

        stage '090-launch-bg-scripts'
        step090_launch_bg_scripts(SCRIPT_DIR, job['background_scripts'])

        stage '100-run-gatling-sim'
        step100_run_gatling_sim(job_name,
                job["gatling_simulation_config"],
                SCRIPT_DIR)

        stage '105-stop-bg-scripts'
        step105_stop_bg_scripts(SCRIPT_DIR, job['background_scripts'])

        stage '110-collect-sut-artifacts'
        step110_collect_sut_artifacts(SCRIPT_DIR, job_name, job['archive_sut_files'])

        stage '900-collect-driver-artifacts'
        step900_collect_driver_artifacts()
    }
}

def multipass_pipeline(jobs) {
    node {
        checkout scm

        SKIP_SERVER_INSTALL = (SKIP_SERVER_INSTALL == "true")
        SKIP_PROVISIONING = (SKIP_PROVISIONING == "true")

        // NOTE: jenkins does not appear to like groovy's
        // closure syntax:
        //  `jobs.each { job ->`
        // so we use a regular for loop instead.
        for (job in jobs) {
            job_name = job['job_name']

            echo "RUNNING JOB:" + job_name

            stage job_name
            step000_provision_sut(SKIP_PROVISIONING, SCRIPT_DIR)
            step010_setup_beaker(SCRIPT_DIR, job["server_version"])
            server_era = get_server_era(job["server_version"])
            step020_install_server(SKIP_SERVER_INSTALL, SCRIPT_DIR, server_era)
            step025_collect_facter_data(job_name,
                    job['gatling_simulation_config'],
                    SCRIPT_DIR,
                    server_era)
            step040_install_puppet_code(SCRIPT_DIR, job["code_deploy"], server_era)
            step045_install_hiera_config(SCRIPT_DIR, job["code_deploy"], server_era)
            step050_file_sync(SCRIPT_DIR, server_era)
            step060_classify_nodes(SCRIPT_DIR,
                    job['gatling_simulation_config'],
                    server_era)
            step070_validate_classification()
            step075_customize_puppet_settings(SCRIPT_DIR, job['puppet_settings'])
            step080_customize_java_args(SCRIPT_DIR,
                    job["server_java_args"],
                    server_era)
            step081_customize_jruby_jar(SCRIPT_DIR,\
                    job["jruby_jar"],
                    server_era)
            step085_customize_hocon_settings(SCRIPT_DIR, job['hocon_settings'], server_era)
            step090_launch_bg_scripts(SCRIPT_DIR, job['background_scripts'])
            step100_run_gatling_sim(job_name,
                    job['gatling_simulation_config'],
                    SCRIPT_DIR)
            step105_stop_bg_scripts(SCRIPT_DIR, job['background_scripts'])
            step110_collect_sut_artifacts(SCRIPT_DIR, job_name, job['archive_sut_files'])
        }

        // it's critical that the gatling archiving happens outside
        // of the loop.  If it happens inside of the loop, the first
        // run will be archived and the second one will hit a minor
        // error that prevents the summary graph from showing both
        // sets of results.
        step900_collect_driver_artifacts()
    }
}

return this;
