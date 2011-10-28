#!/bin/bash

############################################################
# Config Options

# Minutes between updates
UPDATE_INTERVAL=23
# File used to track last update
LAST_UPDATED_FILE=lastupdated
# STDOUT log File
LOG_FILE=../log/PKBricks.out

############################################################

#Check if this update should be forced
FORCE_UPDATE="false"
while getopts ":f" opt; do
    case $opt in
        f)
            FORCE_UPDATE="true"
        ;;
        \?)
            echo "Invalid option: -$OPTARG" >&2
            exit -1
        ;;
    esac
done

# Absolute path to this script.
SCRIPT=$(readlink -f $0)
# Absolute path this script is in.
SCRIPTPATH=`dirname $SCRIPT`
pushd $SCRIPTPATH

#Get the current time and calculate how old the status file needs to be to trigger an update
NOW_IN_SEC=`date +%s`
NOW=`date`
NEXT_UPDATE=$(( $NOW_IN_SEC - (($UPDATE_INTERVAL * 60) - 30) ))

#Get the timestamp of the status file
if [ -f $LAST_UPDATED_FILE ]; then
    LAST_UPDATED_FILE__UPDATED=`stat --format=%Y $LAST_UPDATED_FILE`
else
    LAST_UPDATED_FILE__UPDATED=0
fi

#If a forced update or the last update file is old enough call the script
if [ $FORCE_UPDATE == "true" -o $LAST_UPDATED_FILE__UPDATED -le $NEXT_UPDATE ]; then 
    pushd src
    echo "$NOW: $(( ($NOW_IN_SEC - $LAST_UPDATED_FILE__UPDATED) / 60 )) minutes have passed since the last update, updating now" >> $LOG_FILE 2>$1
    if [ $FORCE_UPDATE == "true" ] then
        /usr/bin/nice -n 19 /usr/bin/groovy PKBricks.groovy -f >> $LOG_FILE 2>&1
    else
        /usr/bin/nice -n 19 /usr/bin/groovy PKBricks.groovy >> $LOG_FILE 2>&1
    fi

    popd

    touch $LAST_UPDATED_FILE
fi

popd
