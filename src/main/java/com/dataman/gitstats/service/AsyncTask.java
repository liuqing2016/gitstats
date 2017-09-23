package com.dataman.gitstats.service;

import java.util.*;
import java.util.concurrent.Future;

import com.dataman.gitstats.po.PushEventRecord;
import com.dataman.gitstats.repository.PushEventRecordRepository;
import org.gitlab4j.api.GitLabApi;
import org.gitlab4j.api.GitLabApiException;
import org.gitlab4j.api.models.Commit;
import org.gitlab4j.api.models.CommitStats;
import org.gitlab4j.api.webhook.EventCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;

import com.dataman.gitstats.po.CommitStatsPo;
import com.dataman.gitstats.po.ProjectBranchStats;
import com.dataman.gitstats.repository.CommitStatsRepository;
import com.dataman.gitstats.repository.ProjectBranchStatsRepository;
import com.dataman.gitstats.util.ClassUitl;
import com.dataman.gitstats.util.GitlabUtil;


@Component
public class AsyncTask {

	protected Logger logger = LoggerFactory.getLogger(this.getClass());
	
	@Autowired
	GitlabUtil gitlabUtil;
	
	@Autowired
	ProjectBranchStatsRepository projectBranchStatsRepository;

	@Autowired
	CommitStatsRepository commitStatsRepository;

	@Autowired
	PushEventRecordRepository pushEventRecordRepository;
	
	/**
	 * @method initProjectStats(初始化数据)
	 * @return String
	 * @author liuqing
	 * @throws GitLabApiException 
	 * @throws Exception 
	 * @date 2017年9月19日 下午4:31:20
	 */
	@Async
	public Future<String> initProjectStats(ProjectBranchStats pbs) throws GitLabApiException{
		logger.info("初始化开始:"+pbs.getProjectname()+"."+pbs.getBranch());
		Calendar cal =Calendar.getInstance();
		long begin = System.currentTimeMillis();
		int addRow=0,removeRow=0;
		int projectId= pbs.getProid();
		String branch=pbs.getBranch();
		String pid=pbs.getProjectid();
		// 清理数据 
		commitStatsRepository.deleteByProidAndBranch(pid, branch);
		GitLabApi gitLabApi=  gitlabUtil.getGitLabApi(pbs.getAccountid());
		//获取当前项目当前分支的所有commit
		Date currdate=cal.getTime();
		cal.set(2000, 1, 1);
		Date begindate=cal.getTime();
		List<Commit> list= gitLabApi.getCommitsApi().getCommits(projectId, branch, begindate, currdate);
		for (Commit commit : list) {
			CommitStatsPo csp=new CommitStatsPo();
			try {
				csp=ClassUitl.copyProperties(commit, csp);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				logger.info(e.getMessage());
			}
			Set<String> commitBranch=new HashSet<>();
			commitBranch.add(branch);
			csp.setBranch(commitBranch);
			csp.setProid(pid);
			Commit sigleCommit= gitLabApi.getCommitsApi().getCommit(projectId, commit.getId());
			CommitStats stats= sigleCommit.getStats();
			csp.setAddRow(stats.getAdditions());
			csp.setRemoveRow(stats.getDeletions());
			csp.setCrateDate(new Date());
			commitStatsRepository.insert(csp);
			addRow+=stats.getAdditions();
			removeRow+=stats.getDeletions();
		}	
		pbs.setStatus(1);
		pbs.setTotalAddRow(addRow);
		pbs.setTotalDelRow(removeRow);
		pbs.setTotalRow(addRow-removeRow);
		pbs.setLastupdate(currdate);
		try {
			projectBranchStatsRepository.save(pbs);  //保存跟新记录
		} catch (Exception e) {
			logger.info(e.getMessage());
		}
		logger.info("update success");
		long usetime = begin-System.currentTimeMillis();
		logger.info("初始化"+pbs.getProjectname()+"."+pbs.getBranch()+"完成耗时:"+usetime+"ms");
		return new AsyncResult<String>("初始化完成");  
	}

	@Async
	public void saveCommitStatsFromEventCommitsList(PushEventRecord record,ProjectBranchStats projectBranchStats,List<EventCommit> eventCommitList) throws Exception {
		GitLabApi gitLabApi=gitlabUtil.getGitLabApi(projectBranchStats.getAccountid());
			while (projectBranchStats.getStatus()==0){
				Thread.sleep(1000);
				projectBranchStats=projectBranchStatsRepository.findOne(projectBranchStats.getId());
			}
		projectBranchStats.setStatus(0);
		projectBranchStatsRepository.save(projectBranchStats);
		CommitStatsPo commitStats;
		for(EventCommit eventCommit:eventCommitList){
			commitStats=commitStatsRepository.findOne(eventCommit.getId());
			if(commitStats==null){
				Commit commit=gitLabApi.getCommitsApi().getCommit(projectBranchStats.getProid(),eventCommit.getId());
				commitStats=new CommitStatsPo();
				ClassUitl.copyPropertiesExclude(commit, commitStats, new String[]{"parentIds","stats"});
				commitStats.setProid(projectBranchStats.getProjectid());
				Set<String> branch=new HashSet<>();
				branch.add(projectBranchStats.getBranch());
				commitStats.setBranch(branch);
				commitStats.setAddRow(commit.getStats().getAdditions());
				commitStats.setRemoveRow(commit.getStats().getDeletions());
				commitStats.setCrateDate(new Date());

				projectBranchStats.setTotalAddRow(projectBranchStats.getTotalAddRow()+commit.getStats().getAdditions());
				projectBranchStats.setTotalDelRow(projectBranchStats.getTotalDelRow() + commit.getStats().getDeletions());
				projectBranchStats.setTotalRow(projectBranchStats.getTotalAddRow()-projectBranchStats.getTotalDelRow());
				projectBranchStatsRepository.save(projectBranchStats);
			}else{
				commitStats.getBranch().add(projectBranchStats.getBranch());
			}
			commitStatsRepository.save(commitStats);
		}
		projectBranchStats.setStatus(1);
		projectBranchStatsRepository.save(projectBranchStats);
		record.setStatus(record.FINISHED);
		record.setUpdateAt(new Date());
		pushEventRecordRepository.save(record);
	}
	
	
}
