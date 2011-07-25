/*
 * Copyright 2011 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.wagstrom.research.github;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.api.v2.schema.Issue;
import com.github.api.v2.schema.PullRequest;
import com.github.api.v2.schema.Team;
import com.github.api.v2.services.FeedService;
import com.github.api.v2.services.GitHubException;
import com.github.api.v2.services.GitHubServiceFactory;

/**
 * Main driver class for GitHub data processing.
 * 
 * @author Patrick Wagstrom (http://patrick.wagstrom.net/)
 *
 */
public class GitHubMain {
	Logger log = null;
	ApiThrottle throttle = null;
	public GitHubMain() {
		log = LoggerFactory.getLogger(this.getClass());		
        throttle = new ApiThrottle();
	}
	
	public void main() {

		ArrayList <String> projects = new ArrayList<String> ();
		ArrayList <String> users = new ArrayList<String> ();
		ArrayList <String> organizations = new ArrayList<String> ();
		GitHubServiceFactory factory = GitHubServiceFactory.newInstance();
		
		Properties p = GithubProperties.props();
		try {
			for (String proj : p.getProperty("net.wagstrom.research.github.projects").split(",")) {
				projects.add(proj.trim());
			}
		} catch (NullPointerException e) {
			log.error("property net.wagstrom.research.github.projects undefined");
			System.exit(1);
		}
		
		try{
			for (String user : p.getProperty("net.wagstrom.research.github.users").split(",")) {
				users.add(user.trim());
			}
		} catch (NullPointerException e) {
			log.error("property net.wagstrom.research.github.users undefined");
			System.exit(1);
		}
		
		try {
			for (String organization : p.getProperty("net.wagstrom.research.github.organizations").split(",")){
				organizations.add(organization.trim());
			}
		} catch (NullPointerException e) {
			log.error("property net.wagstrom.research.github.organizations undefined");
			System.exit(1);
		}
		
		BlueprintsDriver bp = connectToGraph(p);

		RepositoryMiner rm = new RepositoryMiner(ThrottledGitHubInvocationHandler.createThrottledRepositoryService(factory.createRepositoryService(), throttle));
		IssueMiner im = new IssueMiner(ThrottledGitHubInvocationHandler.createThrottledIssueService(factory.createIssueService(), throttle));
		PullMiner pm = new PullMiner(ThrottledGitHubInvocationHandler.createThrottledPullRequestService(factory.createPullRequestService(), throttle));
		
		if (p.getProperty("net.wagstrom.research.github.miner.repositories","true").equals("true")) {
			for (String proj : projects) {
				String [] projsplit = proj.split("/");
				bp.saveRepository(rm.getRepositoryInformation(projsplit[0], projsplit[1]));
				if (p.getProperty("net.wagstrom.research.github.miner.repositories.collaborators", "true").equals("true"))
					bp.saveRepositoryCollaborators(proj, rm.getRepositoryCollaborators(projsplit[0], projsplit[1]));
				if (p.getProperty("net.wagstrom.research.github.miner.repositories.contributors", "true").equals("true"))
					bp.saveRepositoryContributors(proj, rm.getRepositoryContributors(projsplit[0], projsplit[1]));
				if (p.getProperty("net.wagstrom.research.github.miner.repositories.watchers", "true").equals("true"))
					bp.saveRepositoryWatchers(proj, rm.getWatchers(projsplit[0], projsplit[1]));
				if (p.getProperty("net.wagstrom.research.github.miner.repositories.forks", "true").equals("true"))
					bp.saveRepositoryForks(proj, rm.getForks(projsplit[0], projsplit[1]));
				if (p.getProperty("net.wagstrom.research.github.miner.issues","true").equals("true")) {
					Collection<Issue> issues = im.getAllIssues(projsplit[0], projsplit[1]);
					bp.saveRepositoryIssues(proj, issues);
					for (Issue issue : issues) {
						bp.saveRepositoryIssueComments(proj, issue, im.getIssueComments(projsplit[0], projsplit[1], issue.getNumber()));
					}
				}
				if (p.getProperty("net.wagstrom.research.github.miner.pullrequests", "true").equals("true")) {
					Collection<PullRequest> requests = pm.getPullRequests(projsplit[0], projsplit[1]);
					// bp.saveRepositoryPullRequests(proj, requests);
					for (PullRequest request : requests) {
						if (request.getNumber() == 408) {
							log.info("Fetching pull request 408");
							bp.saveRepositoryPullRequest(proj, pm.getPullRequest(projsplit[0], projsplit[1], request.getNumber()));
						}
					}
				}
			}
		}

		UserMiner um = new UserMiner(ThrottledGitHubInvocationHandler.createThrottledUserService(factory.createUserService(), throttle));
		GistMiner gm = new GistMiner(ThrottledGitHubInvocationHandler.createThrottledGistService(factory.createGistService(), throttle));
		if (p.getProperty("net.wagstrom.research.github.miner.users","true").equals("true")) {
			for (String user : users) {
				bp.saveUser(um.getUserInformation(user));
				bp.saveUserFollowers(user, um.getUserFollowers(user));
				bp.saveUserFollowing(user, um.getUserFollowing(user));
				bp.saveUserWatchedRepositories(user, um.getWatchedRepositories(user));
				bp.saveUserRepositories(user, rm.getUserRepositories(user));
				if (p.getProperty("net.wagstrom.research.github.miner.gists","true").equals("true")) {
					bp.saveUserGists(user, gm.getUserGists(user));
				}
			}
		}
	
		OrganizationMiner om = new OrganizationMiner(ThrottledGitHubInvocationHandler.createThrottledOrganizationService(factory.createOrganizationService(), throttle));
		if (p.getProperty("net.wagstrom.research.github.miner.organizations","true").equals("true")) {
			for (String organization : organizations) {
				bp.saveOrganization(om.getOrganizationInformation(organization));
				// This method fails when you're not an administrator of the organization
	//			try {
	//				bp.saveOrganizationOwners(organization, om.getOrganizationOwners(organization));
	//			} catch (GitHubException e) {
	//				log.info("Unable to fetch owners: {}", GitHubErrorPrimative.createGitHubErrorPrimative(e).getError());
	//			}
				bp.saveOrganizationPublicMembers(organization, om.getOrganizationPublicMembers(organization));
				bp.saveOrganizationPublicRepositories(organization, om.getOrganizationPublicRepositories(organization));
				// This fails when not an administrator of the organization
	//			try {
	//				List<Team> teams = om.getOrganizationTeams(organization);
	//				bp.saveOrganizationTeams(organization, teams);
	//				for (Team team : teams) {
	//					bp.saveTeamMembers(team.getId(), om.getOrganizationTeamMembers(team.getId()));
	//					bp.saveTeamRepositories(team.getId(), om.getOrganizationTeamRepositories(team.getId()));
	//				}
	//			} catch (GitHubException e) {
	//				log.info("Unable to fetch teams: {}", GitHubErrorPrimative.createGitHubErrorPrimative(e).getError());
	//			}
			}
		}

		log.info("Shutting down graph");
		bp.shutdown();
	}
	
	private BlueprintsDriver connectToGraph(Properties p) {
		BlueprintsDriver bp = null;
		
		try {
			String dbengine = p.getProperty("net.wagstrom.research.github.dbengine").trim();
			String dburl = p.getProperty("net.wagstrom.research.github.dburl").trim();
			bp = new BlueprintsDriver(dbengine, dburl);
		} catch (NullPointerException e) {
			log.error("properties undefined, must define both net.wagstrom.research.github.dbengine and net.wagstrom.research.github.dburl");
		}
		return bp;
	}
}