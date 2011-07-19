@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.5.1' )
@Grab(group='log4j', module='log4j', version='1.2.16' )
@Grab(group='net.sf.json-lib', module='json-lib', version='2.4', classifier='jdk15')
@Grab(group='commons-lang', module='commons-lang', version='2.6')

import org.apache.commons.lang.*;
import net.sf.*;
import net.sf.json.*;
import net.sf.json.groovy.*;
import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.GET
import static groovyx.net.http.ContentType.TEXT
import static groovyx.net.http.ContentType.HTML
import static groovyx.net.http.ContentType.URLENC


GJson.enhanceClasses();
GJson.enhanceString();

/*
download this weeks json from s3
if fail load base json
*/

//Create a JSON parser
def jsonSlurper = new JsonSlurper();

//Create a Calendar that corresponds to the Sunday of this week
def cal = Calendar.getInstance();
cal.set(Calendar.HOUR, 0);
cal.set(Calendar.MINUTE, 0);
cal.set(Calendar.SECOND, 0);
cal.set(Calendar.MILLISECOND, 0);
cal.add(Calendar.DAY_OF_YEAR, -1 * Math.max(0, cal.get(Calendar.DAY_OF_WEEK) - 1));
def brickTimeStamp = cal.getTimeInMillis();

def dataFolder = "/Users/edalquist/java/workspace_37/PKBricks/src/";
def historyFile = dataFolder + "history.json";
def brickDefaultsFile = dataFolder + "brickStatusDefaults.json";

def history = jsonSlurper.parse("file:" + historyFile);
def brickDataFilename = history[brickTimeStamp.toString()];
def brickDataFile = dataFolder + brickDataFilename;
def brickData;
if (brickDataFilename == null) {
    brickDataFilename = "brickStatus_" + 
        cal.get(Calendar.YEAR) + 
        StringUtils.leftPad(String.valueOf(cal.get(Calendar.MONTH) + 1), 2, '0') + 
        cal.get(Calendar.DAY_OF_MONTH) + ".json";

    brickDataFile = dataFolder + brickDataFilename;
    println "No existing brick data in history for " + brickTimeStamp + ". Creating: " + brickDataFilename;
    brickData = jsonSlurper.parse("file:" + brickDefaultsFile);
}
else {
    brickData = jsonSlurper.parse("file:" + brickDataFile);
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
loginPostBody['ctl00$MainContent$CharacterName']="TnGoat";
loginPostBody['ctl00$MainContent$Password']="drick17";

//Submit login
http.post(path: '/' + loginPath, body: loginPostBody, requestContentType: URLENC );

//Get the KD Profile page again
kdProfile = http.get(path : '/Kingdom.aspx', query : [kingdomid:'232']);

//Find the member table
def memberTable = kdProfile.depthFirst().grep({ "table".equalsIgnoreCase(it.name()) && it.@id == "MainContent_KingdomBody_memberListTable"})[0];

//Read each row of table
def rowCount = 0;
memberTable.depthFirst().grep({"tr".equalsIgnoreCase(it.name())}).each {
    //Skip header data 
    if (rowCount < 2) {
        rowCount++;
        return;
    }
    
    def cells = it.children();
    def firstCell = cells[0].children()[0];
    def profileUrl = firstCell['@href'].text();
    def profilePage = http.get(uri : 'http://www.parallelkingdom.com/' + profileUrl);
    
    def profileLevel = profilePage.depthFirst().grep({ "span".equalsIgnoreCase(it.name()) && it.@id == "MainContent_ProfileBody_character_level"})[0];
    
    def playerName = firstCell.text().trim();
    def playerLevel = Integer.parseInt(profileLevel.text());
    def brickEarned = Integer.parseInt(cells[4].text().trim());
    def brickSpent = Integer.parseInt(cells[5].text().trim());

    println playerName + ", " + playerLevel + ", " + brickEarned + ", " + brickSpent;
    
    //Determine the group the player is in
    def playerGroup = brickData.playerGroupLookup[playerName];
    if (playerGroup == null) {
        playerGroup = brickGroups[playerLevel];
        brickData.playerGroupLookup[playerName] = playerGroup;
        println playerName + " in group " + playerGroup;
    }
    
    //Update the data for the player
    brickData.groups[playerGroup][playerName] = [
        level: playerLevel, 
        earned: brickEarned,
        spent: brickSpent];
};

def bdf = new File(brickDataFile);
bdf.delete();
bdf << brickData.toString(2);

/*
write out json
upload to s3
update registry json from s3
*/