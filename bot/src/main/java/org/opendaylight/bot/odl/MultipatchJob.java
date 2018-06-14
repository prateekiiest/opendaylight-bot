/*
 * Copyright (c) 2018 Red Hat, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.bot.odl;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.opendaylight.bot.BotException;
import org.opendaylight.bot.Projects;
import org.opendaylight.bot.ResultWithWarnings;
import org.opendaylight.bot.gerrit.Gerrit;
import org.opendaylight.bot.math.DependencySorter;
import org.opendaylight.bot.math.ParentFunction;

/**
 * Utilities related to the OpenDaylight integration-multipatch-test job.
 *
 * @author Michael Vorburger.ch
 */
public class MultipatchJob {

    private static final String REF_CHANGES_PREFIX = "refs/changes/";

    @VisibleForTesting
    static final ParentFunction<ChangeInfo> CHANGE_PARENT_FUNCTION = (first, second) ->  {
        String firstParentCommit = Gerrit.getCurrentParentCommit(first);
        String secondCurrentCommit = Gerrit.getCurrentCommit(second);
        return secondCurrentCommit.equals(firstParentCommit);
    };

    private final Projects projects;

    public MultipatchJob(Projects projects) {
        this.projects = projects;
    }

    /**
     * Constructs the String used in the PATCHES_TO_BUILD parameter of an
     * OpenDaylight integration-multipatch-test job.
     */
    @SuppressFBWarnings("UC_USELESS_OBJECT") // apparently a bug in FindBugs; map2 is not useless, of course
    public ResultWithWarnings<String, String> getPatchesToBuildString(Collection<ChangeInfo> changes)
            throws BotException {
        if (changes.stream().filter(change -> change.mergeable != null && !change.mergeable).findFirst().isPresent()) {
            return new ResultWithWarnings<>("",
                    "Refusing build as long as there are changes with conflicts, please rebase and resolve them.");
        }

        // TODO check for +1 Verified Vote, and abort if not present

        Map<String, List<ChangeInfo>> unfilteredMap = newMultimap();

        changes.stream()
            .filter(change -> change.status.equals(ChangeStatus.NEW))
            .forEach(change -> {
                List<ChangeInfo> projectChangeList = unfilteredMap.get(change.project);
                if (projectChangeList != null) {
                    projectChangeList.add(change);
                }
            });

        // remove leading projects which have no changes
        Map<String, List<ChangeInfo>> filteredMap = new LinkedHashMap<>(unfilteredMap); // must preserve order!
        for (Map.Entry<String, List<ChangeInfo>> entry : unfilteredMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                filteredMap.remove(entry.getKey());
            } else {
                break;
            }
        }

        // sort changes, per project, based on parent commit order (if any matches)
        filteredMap.forEach((project, projectChangeList) ->
            DependencySorter.sort(projectChangeList, CHANGE_PARENT_FUNCTION));

        StringBuilder patchesToBuild = new StringBuilder();
        filteredMap.forEach((project, refs) -> {
            if (patchesToBuild.length() > 0) {
                patchesToBuild.append(",");
            }
            patchesToBuild.append(project);
            if (!refs.isEmpty()) {
                patchesToBuild.append(':');
                Iterator<ChangeInfo> iterator = refs.iterator();
                apend(patchesToBuild, iterator.next());
                while (iterator.hasNext()) {
                    patchesToBuild.append(':');
                    apend(patchesToBuild, iterator.next());
                }
            }
        });

        List<String> unknownProjects = changes.stream()
                .filter(change -> !unfilteredMap.containsKey(change.project))
                .map(change -> change.project).distinct().collect(Collectors.toList());
        return new ResultWithWarnings<>(patchesToBuild.toString(),
                unknownProjects.stream().map(project -> "Ignored unknown project: " + project)
                        .collect(Collectors.toList()));
    }

    private ImmutableMap<String, List<ChangeInfo>> newMultimap() {
        // The Map we return has to be both immutable, and has predictable iteration order based on insertion.
        // We could use a LinkedHashMap, but as ImmutableMap with a Builder also guarantees order, let's use that:
        Builder<String, List<ChangeInfo>> builder = ImmutableMap.<String, List<ChangeInfo>>builder();
        projects.getProjects().forEach(projectName -> builder.put(projectName, new ArrayList<>()));
        return builder.build();
    }

    private static void apend(StringBuilder patchesToBuild, ChangeInfo change) {
        patchesToBuild.append(Gerrit.getCurrentRevisionReference(change).substring(REF_CHANGES_PREFIX.length()));
    }
}
