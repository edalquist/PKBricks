@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.1' )
@Grab(group='log4j', module='log4j', version='1.2.16' )
@Grab(group='commons-lang', module='commons-lang', version='2.6')
@Grab(group='commons-io', module='commons-io', version='2.0.1')
@Grab(group='net.java.dev.jets3t', module='jets3t', version='0.8.1')

import org.apache.commons.lang.*;
import org.apache.commons.io.*;
import groovy.json.*;
import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.ContentType.HTML
import static groovyx.net.http.ContentType.URLENC
import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.security.AWSCredentials
import org.jets3t.service.model.*

final def historyFileName = "history.json";
final def brickStatusDefaultsFileName = "brickStatusDefaults.json";
final def encoding = "UTF-8";

//Create a JSON parser
def jsonSlurper = new JsonSlurper();

def scriptDir = new File(getClass().protectionDomain.codeSource.location.path).parent;
def config = jsonSlurper.parseText(new File(scriptDir, "config.json").getText(encoding));

//Create a Calendar that corresponds to the Sunday of this week
def cal = Calendar.getInstance();
cal.set(Calendar.HOUR_OF_DAY, 0);
cal.set(Calendar.MINUTE, 0);
cal.set(Calendar.SECOND, 0);
cal.set(Calendar.MILLISECOND, 0);
cal.add(Calendar.DAY_OF_YEAR, -1 * Math.max(0, cal.get(Calendar.DAY_OF_WEEK) - 1));
def brickTimeStamp = cal.getTimeInMillis().toString();

//Setup temp dir for json manipulation
def dataFolder = File.createTempFile("PKBricks.", ".registry");
dataFolder.delete();
dataFolder.mkdir();


def awsLogin = new AWSCredentials( config.S3.accessKey, config.S3.secretKey )
def s3 = new RestS3Service( awsLogin )

def historyFile = new File(dataFolder, historyFileName);

if (s3.isObjectInBucket(config.S3.bucketName, historyFileName)) {
    def historyS3Obj = s3.getObject(config.S3.bucketName, historyFileName);
    historyS3Stream = historyS3Obj.getDataInputStream();
    historyFileStream = new FileOutputStream(historyFile);
    IOUtils.copy(historyS3Obj.getDataInputStream(), historyFileStream);
    IOUtils.closeQuietly(historyS3Stream);
    IOUtils.closeQuietly(historyFileStream);
}

def brickDefaultsFile = new File(scriptDir, brickStatusDefaultsFileName);

def history;
if (historyFile.exists()) {
    history = jsonSlurper.parseText(historyFile.getText(encoding));
}
else {
    history = [:];
}

def brickDataFilename = history[brickTimeStamp];
def brickDataFile;
def brickData;
if (brickDataFilename == null) {
    brickDataFilename = "brickStatus_" + 
        cal.get(Calendar.YEAR) + 
        StringUtils.leftPad(String.valueOf(cal.get(Calendar.MONTH) + 1), 2, '0') + 
        cal.get(Calendar.DAY_OF_MONTH) + ".json";

    brickDataFile = new File(dataFolder, brickDataFilename);
    println "No existing brick data in history for " + brickTimeStamp + ". Using: " + brickDefaultsFile;
    brickData = jsonSlurper.parseText(brickDefaultsFile.getText(encoding));
    history[brickTimeStamp] = brickDataFilename;
}
else {
    brickDataFile = new File(dataFolder, brickDataFilename);
    brickData = jsonSlurper.parseText(brickDataFile.getText(encoding));
}

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
 
//Get KD Profile Page
def kdProfile = http.get(path : '/Kingdom.aspx', query : [kingdomid:'232']);

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
kdProfile = http.get(path : '/Kingdom.aspx', query : [kingdomid:'232']);

//Find the member table
def memberTable = kdProfile.depthFirst().grep({ "table".equalsIgnoreCase(it.name()) && it.@id == "MainContent_KingdomBody_memberListTable"})[0];

//Read each row of table
def rowCount = 0;
memberTable.depthFirst().grep({"tr".equalsIgnoreCase(it.name())}).each {
    //Skip header rows (not sure why we find two ...) 
    if (rowCount < 2) {
        rowCount++;
        return;
    }
    
    def cells = it.children();
    def firstCell = cells[0].children()[0];

    def playerName = firstCell.text().trim();
    def brickEarned = Integer.parseInt(cells[4].text().trim());
    def brickSpent = Integer.parseInt(cells[5].text().trim());

    println playerName + ", " + brickEarned + ", " + brickSpent;
    
    //See if the player exists in the data file, if not we need to look up their level
    def playerGroup = brickData.playerGroupLookup[playerName];
    def playerLevel;
    if (playerGroup == null || brickData.groups[playerGroup][playerName] == null) {
        def profileUrl = firstCell['@href'].text();
        def profilePage = http.get(uri : 'http://www.parallelkingdom.com/' + profileUrl);
        def profileLevel = profilePage.depthFirst().grep({ "span".equalsIgnoreCase(it.name()) && it.@id == "MainContent_ProfileBody_character_level"})[0];
        playerLevel = Integer.parseInt(profileLevel.text());
    
        if (playerGroup == null) {
            playerGroup = brickGroups[playerLevel];
            brickData.playerGroupLookup[playerName] = playerGroup;
        }
        println playerName + ", " + playerLevel + " in group " + playerGroup;
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

brickDataFile.write(JsonOutput.prettyPrint(JsonOutput.toJson(brickData)), encoding);

println "HISTORY FILE";
println JsonOutput.prettyPrint(JsonOutput.toJson(history));
historyFile.write(JsonOutput.prettyPrint(JsonOutput.toJson(history)), encoding);

def historyS3Obj = new StorageObject(historyFile);
historyS3Obj.key = historyFileName;
historyS3Obj.contentType = "applicaton/json";
historyS3Obj.contentEncoding = encoding;

s3.putObject(config.S3.bucketName, historyS3Obj);


dataFolder.deleteDir();

/*
write out json
upload to s3
update registry json from s3
*/