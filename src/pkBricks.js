var toLocaleDate = function(time) {
    time = parseInt(time);
    var d = new Date(time);
    return d.toLocaleDateString();
};

var sortMap = function(data, sortFunc) {
    var sortable = [];
    for ( var key in data) {
        sortable.push([ key, data[key] ]);
    }
    sortable.sort(sortFunc);
    return sortable;
};

var updateNavLink = function(id, index) {
    var link = $(id);
    if (index >= 0 && index < PKBricks.history.length) {
        var historyData = PKBricks.history[index];
        link.attr("href", "#" + historyData[0]);
        link.text(toLocaleDate(historyData[0]));
    }
    else {
        link.attr("href", "");
        link.text("");
    }
};

var displayBrickCount = function(historyIdx) {
    var historyData = PKBricks.history[historyIdx];

    // Update Navigation
    updateNavLink("#prevWeek", historyIdx - 1);
    $("#thisWeek").text(toLocaleDate(historyData[0]));
    updateNavLink("#nextWeek", historyIdx + 1);

    $.getJSON(historyData[1], function(brickData) {
        var totalEarned = 0;
        var totalSpent = 0;

        // create root list of groups
        var groupList = $('<ul/>', {
            'id' : 'groupList'
        });

        // Sort the group data and iterate over it
        var sortedGroups = sortMap(brickData["groups"]);
        $.each(sortedGroups, function(groupsIdx, groupsEntry) {
            var groupName = groupsEntry[0];
            var members = groupsEntry[1];

            // create group entry and child member list
            var group = $('<li/>', {
                'id' : 'group-' + groupName,
                html : groupName
            });
            var memberList = $('<ul/>', {
                'id' : 'memberList-' + groupName
            });

            // Sort members by bricks earned
            var sortedMembers = sortMap(members, function(a, b) {
                return b[1].earned - a[1].earned;
            });
            $.each(sortedMembers, function(membersIdx, memberEntry) {
                // Add each member to the member list
                memberList.append("<li>" + memberEntry[1].earned + " - " + memberEntry[0] + "</li>");

                totalEarned += memberEntry[1].earned;
                totalSpent += memberEntry[1].spent;
            });

            // Add the member list to the group and the group to the group list
            group.append(memberList);
            groupList.append(group);
        });

        // Add the group list to the page
        $("#brickStandings").children().remove();
        groupList.appendTo('#brickStandings');

        // Update the totals
        $("#totalEarned").text(totalEarned);
        $("#totalSpent").text(totalSpent);
        $("span#lastUpdated").text(new Date(brickData.lastUpdated).toLocaleString());
    });
};

var PKBricks = PKBricks || {};
PKBricks.anchorRegex = /[^#]*#(\d*)/g;
PKBricks.history = [];

$(document).ready(function() {
    $.getJSON('history.json', function(historyData) {
        PKBricks.history = sortMap(historyData, function(a, b) {
                return a[0] - b[0];
            });

        $(window).bind('hashchange', function(e) {
            var url = $.param.fragment();
            if (url != "") {
                $.each(PKBricks.history, function(idx, historyData) {
                    if (url == historyData[0]) {
                        displayBrickCount(idx);
                        return;
                    }
                });
            }
            else {
                displayBrickCount(PKBricks.history.length - 1);
            }
        });

        // Since the event is only triggered when the hash changes, we need to
        // trigger
        // the event now, to handle the hash the page may have loaded with.
        $(window).trigger('hashchange');
    });
});