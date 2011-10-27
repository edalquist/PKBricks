/**
 * Copyright 2011 Eric Dalquist
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.1' )
@Grab(group='log4j', module='log4j', version='1.2.16' )
@Grab(group='commons-lang', module='commons-lang', version='2.6')
@Grab(group='commons-io', module='commons-io', version='2.0.1')
@Grab(group='net.java.dev.jets3t', module='jets3t', version='0.8.1')

import static groovyx.net.http.ContentType.HTML
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.ContentType.URLENC
import static groovyx.net.http.Method.GET
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.EncoderRegistry
import groovy.json.*

import java.util.concurrent.TimeUnit
import java.util.zip.*

import org.apache.commons.io.*
import org.apache.commons.lang.*
import org.apache.log4j.*
import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.security.AWSCredentials
import org.jets3t.service.model.*
import static Constants.*

class Constants {
    static final def logger = LogManager.getLogger("PKBricks");
    static final def historyFileName = "history.json";
    static final def brickStatusDefaultsFileName = "brickStatusDefaults.json";
    static final def encoding = "UTF-8";
    static final def jsonSlurper = new JsonSlurper();
}

final def fromS3Obj(s3Obj) {
    def s3Stream = s3Obj.getDataInputStream();
    
    //If GZipped decompress when reading
    if ("gzip".equals(s3Obj.getContentEncoding())) {
        s3Stream = new GZIPInputStream(s3Stream);
    }
    
    try {
        return jsonSlurper.parse(new InputStreamReader(s3Stream, encoding));
    }
    finally {
        IOUtils.closeQuietly(s3Stream);
    }
}

final def compress(String dataStr) {
    def targetStream = new ByteArrayOutputStream()
    def zipStream = new GZIPOutputStream(targetStream)
    zipStream.write(dataStr.getBytes(encoding));
    zipStream.close()
    return targetStream.toByteArray()
}

final def toS3Obj(fileName, data) {
    def dataStr = JsonOutput.prettyPrint(JsonOutput.toJson(data));
    def dataBytes = compress(dataStr);
    
    def s3Obj = new S3Object(fileName);
    s3Obj.contentType = "applicaton/json";
    s3Obj.contentEncoding = "gzip";
    s3Obj.dataInputStream = new ByteArrayInputStream(dataBytes);
    s3Obj.contentLength = dataBytes.length;
    
    return s3Obj;
}

final def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent;
final def config = jsonSlurper.parseText(new File(scriptDir, "config.json").getText(encoding));

//Load update status file if it is specified
def statusFile;
def statusData;
if (config.statusFile != null) {
    statusFile = new File(scriptDir, config.statusFile);
    if (statusFile.exists()) {
        statusData = jsonSlurper.parseText(statusFile.getText(encoding));
    }
    else {
        statusData = [:];
    }
}

def cli = new CliBuilder(usage: 'PKBricks.groovy -[f]')
// Create the list of options.
cli.with {
    h longOpt: 'help', 'Show usage information'
    f longOpt: 'force-update', 'Force update, ignoring status file'
}

def options = cli.parse(args)
if (!options || options.h) {
    cli.usage();
    return;
}

//Check if enough time has passed such that we should do an update
if (options.f) {
    logger.info("Forced Update");
}
else if (config.updateInterval != null && statusData != null && statusData.lastUpdated != null) {
    def updateInterval = TimeUnit.MINUTES.toMillis(config.updateInterval);
    def now = System.currentTimeMillis();
    if ((statusData.lastUpdated + updateInterval) >= now) {
        logger.debug("It has only been " + TimeUnit.MILLISECONDS.toMinutes(now - statusData.lastUpdated) + " minutes since the last update, updates only done every " + config.updateInterval + " minutes, returning.");
        return;
    }
    else {
        logger.info("It has been " + TimeUnit.MILLISECONDS.toMinutes(now - statusData.lastUpdated) + " minutes since the last update, updating now.");
    }
}
else {
    logger.info("First Update");
}

//Create a Calendar that corresponds to the Sunday of this week
def cal = Calendar.getInstance();
cal.set(Calendar.HOUR_OF_DAY, 0);
cal.set(Calendar.MINUTE, 0);
cal.set(Calendar.SECOND, 0);
cal.set(Calendar.MILLISECOND, 0);
cal.add(Calendar.DAY_OF_YEAR, -1 * Math.max(0, cal.get(Calendar.DAY_OF_WEEK) - 1));
def brickTimeStamp = cal.getTimeInMillis().toString();

def awsLogin = new AWSCredentials( config.S3.accessKey, config.S3.secretKey )
def s3 = new RestS3Service( awsLogin )

//Load the history file from S3
def historyModified = false;
final def history;
if (s3.isObjectInBucket(config.S3.bucketName, historyFileName)) {
    def historyS3Obj = s3.getObject(config.S3.bucketName, historyFileName);
    history = fromS3Obj(historyS3Obj);
    logger.info("Loaded history from S3")
}
else {
    historyModified = true;
    history = [:];
    logger.info("Created new history");
}
logger.debug(history.toString());

//Determine the file name for this weeks brick data
def brickDataFilename = history[brickTimeStamp];
if (brickDataFilename == null) {
    brickDataFilename = "brickStatus_" +
        cal.get(Calendar.YEAR) +
        StringUtils.leftPad(String.valueOf(cal.get(Calendar.MONTH) + 1), 2, '0') +
        cal.get(Calendar.DAY_OF_MONTH) + ".json";

    history[brickTimeStamp] = brickDataFilename;
    historyModified = true;
}

//Load this weeks brick data from S3
final def brickData;
if (s3.isObjectInBucket(config.S3.bucketName, brickDataFilename)) {
    def brickDataS3Obj = s3.getObject(config.S3.bucketName, brickDataFilename);
    brickData = fromS3Obj(brickDataS3Obj);
    logger.info("Loaded brick data from S3 for: " + brickDataFilename);
}
else {
    def brickDefaultsFile = new File(scriptDir, brickStatusDefaultsFileName);
    brickData = jsonSlurper.parseText(brickDefaultsFile.getText(encoding));
    logger.info("Using default brick data for: " + brickDataFilename);
}
logger.debug(brickData.toString());

//Parse out levels to point to group names
def brickGroups = [:];
brickData.groups.each({
    def groupName = it.key;
    if (groupName.contains("-")) {
        def levelRange = groupName.split("-");
        def levelStart = Integer.parseInt(levelRange[0]);
        def levelEnd = Integer.parseInt(levelRange[1]);
        for (def level = levelStart; level <= levelEnd; level++) {
            brickGroups[level] = groupName;
        }
    }
});

brickData.lastUpdated = System.currentTimeMillis();

def http = new HTTPBuilder('http://www.parallelkingdom.com');
http.headers.'User-Agent' = 'Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 6.1; Trident/4.0; SLCC2; .NET CLR 2.0.50727; .NET CLR 3.5.30729; .NET CLR 3.0.30729; Media Center PC 6.0; .NET4.0C; .NET4.0E)';

//added setEncoderRegistry to be able to handle special characters in login name or password.
http.setEncoderRegistry(new EncoderRegistry( charset: 'utf-8' ));
 
//Get KD Profile Page
def kdProfile = http.get(path : '/Kingdom.aspx', query : [kingdomid:config.PK.kingdomid]);

//Find the login link
def loginLink = kdProfile.depthFirst().grep({ it.@id == 'LoginLink' })['@href'][0];

//Get the login form
def loginPage = http.get(path : '/' + loginLink);

//Grab the login form submit url and the form inputs
def loginForm = loginPage.depthFirst().grep({ it.@id == 'mainform' })[0];
def loginPath = loginForm['@action'][0];
def loginPostBody = [:]; 
loginForm.depthFirst().grep({"INPUT".equalsIgnoreCase(it.name())}).each {
    loginPostBody[it['@name'].text()]=it['@value'].text();
};

//Add credentials to post body
loginPostBody['ctl00$MainContent$CharacterName']=config.PK.username;
loginPostBody['ctl00$MainContent$Password']=config.PK.password;

//Submit login
http.post(path: '/' + loginPath, body: loginPostBody, requestContentType: URLENC );

//Get the KD Profile page again
kdProfile = http.get(path : '/Kingdom.aspx', query : [kingdomid:config.PK.kingdomid]);

//Find the member table
def memberTable = kdProfile.depthFirst().grep({ "table".equalsIgnoreCase(it.name()) && it.@id == "MainContent_KingdomBody_memberListTable"})[0];

//Read each row of table
def rowCount = 0;
def newUserCount = 0;
memberTable.depthFirst().grep({"tr".equalsIgnoreCase(it.name())}).each {
    rowCount++;
    //Skip header rows (not sure why we find two ...) 
    if (rowCount <= 2) {
        return;
    }
    
    def cells = it.children();
    def firstCell = cells[0].children()[0];

    def playerName = firstCell.text().trim();
    def brickEarned = Integer.parseInt(cells[4].text().trim());
    def brickSpent = Integer.parseInt(cells[5].text().trim());

    logger.debug(playerName + ", " + brickEarned + ", " + brickSpent);
    
    //See if the player exists in the data file, if not we need to look up their level
    def playerGroup = brickData.playerGroupLookup[playerName];
    def playerLevel;
    if (playerGroup == null || brickData.groups[playerGroup][playerName] == null) {
        newUserCount++;
        def profileUrl = firstCell['@href'].text();
        def profilePage = http.get(uri : 'http://www.parallelkingdom.com/' + profileUrl);
        def profileLevel = profilePage.depthFirst().grep({ "span".equalsIgnoreCase(it.name()) && it.@id == "MainContent_ProfileBody_character_level"})[0];
        playerLevel = Integer.parseInt(profileLevel.text());
    
        if (playerGroup == null) {
            playerGroup = brickGroups[playerLevel];
            brickData.playerGroupLookup[playerName] = playerGroup;
        }
        logger.debug(playerName + ", " + playerLevel + " in group " + playerGroup);
    }
    else {
        playerLevel = brickData.groups[playerGroup][playerName].level;
    }
    
    //Update the data for the player
    brickData.groups[playerGroup][playerName] = [
        level: playerLevel, 
        earned: brickEarned,
        spent: brickSpent];
};

def versioningStatus = s3.getBucketVersioningStatus(config.S3.bucketName);
if (!versioningStatus.versioningEnabled) {
    logger.warn("Bucket versioning not enabled, enabling now");
    s3.enableBucketVersioning(config.S3.bucketName);
}

logger.info("Parsed " + (rowCount - 2) + " players including " + newUserCount + " new players");

//Save the brick data file to S3
def brickDataS3Obj = toS3Obj(brickDataFilename, brickData);
s3.putObject(config.S3.bucketName, brickDataS3Obj);
logger.info("Saved brickData in S3: " + brickDataFilename);
logger.debug(brickData.toString());

//Save the history file to S3
if (historyModified) {
    def historyS3Obj = toS3Obj(historyFileName, history);
    s3.putObject(config.S3.bucketName, historyS3Obj);
    logger.info("Saved history in S3");
    logger.debug(history.toString());
}    

//Save the status file
if (statusFile != null) {
    statusData.lastUpdated = System.currentTimeMillis();
    statusFile.write(JsonOutput.prettyPrint(JsonOutput.toJson(statusData)), encoding);
}
