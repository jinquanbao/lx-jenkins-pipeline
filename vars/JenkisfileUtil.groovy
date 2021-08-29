#!groovy

import org.jenkinsci.plugins.workflow.libs.Library


@Library('jenkinsfile-util') _


def call(Map map){
	
	println "执行call..."
	
	//jenkins 传过来的参数
	def params = map['params']
	
	//管道任务
	def pipelineStages = params['pipelineStages'].split(',')
	
	def gitCheckOutStage = 'gitCheckOut'
	
	def bootJarStage = 'bootJar'
	
	def pushImageStage = 'pushImage'
	
	def deployStage = 'deploy'	
	
	//执行管道任务
	for (String str in pipelineStages) {
		
		str = str.stripIndent()
		
		if(str.startsWith("\"")){            
			str = str.substring(1,str.length())			
		}
		if(str.endsWith("\"")){            
			str = str.substring(0,str.length()-1)			
		}
		
		println "开始执行："+str
		
		if(str.equals(gitCheckOutStage) ){
		  //代码检出
		  gitCheckOut(map)		  
		
		}else if(str.equals(bootJarStage) ){
		  //打包
		  bootJar(map)
		  
		}else if(str.equals(pushImageStage) ){
		  //镜像推送
		  pushImage(map)
		  
		}else if(str.equals(deployStage) ){
		  //远程部署
		  deploy(map)
		
		}else{
				   
		  println "不支持的stage！"
			   
		}					
		
	}
}

//代码检出
def gitCheckOut(Map map){
	
	println "执行gitCheckOut..."
	
	//jenkins 传过来的参数
	def params = map['params']
	
	//docker 私有镜像仓库授权信息
	def	gitlabCredentialsId = params['gitlabCredentialsId']
		
	//模块项目，识别主构建的项目、父项目、依赖的项目
	def moduleGitMap = map['moduleGitMap']
			
	//模块项目，识别主构建的项目、父项目、依赖的项目
	def moduleMap = map['moduleMap']
	
	//父项目名称
    def parentModule = moduleMap['parentModule']
	
	println "parentModule====" + parentModule
	
	//校验依赖的项目是否都配置了tag
    moduleGitMap.each{ key,value->
		
		def tag = params[key]
		if(tag == null || tag == '' ){
			throw new IllegalArgumentException("未配置${key}服务的tag")
		}
		value['tag'] = tag.trim()
	}
	
	stage('gitCheckOut') {        
	
		//检出parent模块
		def parent = new File(parentModule)
		if (!parent.exists()) {
			parent.mkdirs()
		}
		
		dir(parentModule) {
		    
			def parentModuleGit = moduleGitMap[parentModule]
			if(parentModuleGit == null){
				throw new IllegalArgumentException("未配置${parentModule}服务的git地址")
			}	
			
			checkout([$class: 'GitSCM', branches: [[name: "*/${parentModuleGit.tag}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], 
			userRemoteConfigs: [[credentialsId: gitlabCredentialsId, url: parentModuleGit.gitUrl ]]])
			
			def commonModules = moduleMap['commonModules']
			
			println "commonModules====" + commonModules
			
			if(commonModules){
				
				//检出依赖的common模块
				for(String commonModule in commonModules){
				
					//检出common模块
					def common = new File(commonModule)
					if (!common.exists()) {
						common.mkdirs()
					}
					
					dir(commonModule) {
						
						def commonModuleGit = moduleGitMap[commonModule]
						if(commonModuleGit == null){
							throw new IllegalArgumentException("未配置${commonModule}服务的git地址")
						}					
						checkout([$class: 'GitSCM', branches: [[name: "*/${commonModuleGit.tag}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], 
						userRemoteConfigs: [[credentialsId: gitlabCredentialsId, url: commonModuleGit.gitUrl]]])
					}				
				}			
			}
			
			def module = moduleMap['module']
			
			//检出主构建模块			
			def moduleFile = new File(module)
			if (!moduleFile.exists()) {
				moduleFile.mkdirs()
			}
			
			dir(module) {
			
				def moduleGit = moduleGitMap[module]
				if(moduleGit == null){
					row new IllegalArgumentException("未配置${module}服务的git地址")
				}	
				checkout([$class: 'GitSCM', branches: [[name: "*/${moduleGit.tag}"]], doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], 
				userRemoteConfigs: [[credentialsId: gitlabCredentialsId , url: moduleGit.gitUrl]]])
			}
		}
		
	}
	
}

//编译打包
def bootJar(Map map){
	
	println "执行bootJar..."
	
	//模块项目，识别主构建的项目、父项目、依赖的项目
	def moduleMap = map['moduleMap']
	
	//父项目名称
    def parentModule = moduleMap['parentModule']

	//主构建项目名称
	def module = moduleMap['module']
	
	stage('bootJar') {		
		dir(parentModule) {
			dir(module) {
				//gradle打包jar
				def buildScript = """\
				if [ -f ../gradlew ]; then
				#使用parent的gradle wrapper
					echo "找到了 gradlew"
					chmod +x ../gradlew
					../gradlew  clean bootJar
				else
					echo "没有找到parent工程下的gradle打包插件！"
					exit 1
				fi
				""".stripIndent()

				sh buildScript			
			}		
		}
	}
}

//构建镜像并推送到私有镜像仓库
def pushImage(Map map){

	println "执行pushImage..."
	
	//jenkins 传过来的参数
	def params = map['params']
	
	//docker私服registry地址
    def registry = params['registry']
	
	//docker 私有镜像仓库授权信息
	def registryCredentialsId = params['registryCredentialsId']
	
	//模块项目，识别主构建的项目、父项目、依赖的项目
	def moduleMap = map['moduleMap']
	
	//父项目名称
    def parentModule = moduleMap['parentModule']
	
	//主构建项目名称
	def module = moduleMap['module']
	
	//编译项目标签
    def moduleTag = params[module]
	
	//镜像标签
	def imageTag = "${module}:${moduleTag}"	

	stage('pushImage') {
		
		dir(parentModule) {
			dir(module) {				

				//jar包名字
				def jarName = sh script: "ls build/libs | grep ${module}", returnStdout: true
				jarName = jarName.trim()
				sh "echo ${jarName}"
				if (jarName == null)
					throw new IllegalStateException("打包未成功，无法找到jar文件！")												                
				
				sh "echo  imageTag===${imageTag}"
				sh "echo ${registry}"

				
				//构建docker镜像
				sh "docker build -t ${registry}/${imageTag} ."
				
				
				//登录私有镜像仓库并推送
				withCredentials([usernamePassword(credentialsId: registryCredentialsId, passwordVariable: 'dockerPassword', usernameVariable: 'dockerUser')]) {
					sh "docker login -u ${dockerUser} -p ${dockerPassword} ${registry}"				
					sh "docker push ${registry}/${imageTag}"
				}
								
				//推送至私服后删除本地镜像
				sh "docker image rm ${registry}/${imageTag}"
								
			}
		}
		
    }

}

//部署
def deploy(Map map){
	
	println "执行deploy..."
	
	//jenkins 传过来的参数
	def params = map['params']
	
	//docker私服registry地址
    def registry = params['registry']
    				
	//docker 私有镜像仓库授权信息
	def registryCredentialsId = params['registryCredentialsId']
	
	//docker 容器部署地址授权信息
	def deployedCredentialsId = params['deployedCredentialsId']

    //docker容器部署服务地址
    def deployHosts = params['deployHosts']
	
	//docker容器服务启动参数
	def dockerRunArgs = params['dockerRunArgs']
	
	//模块项目，识别主构建的项目、父项目、依赖的项目
	def moduleMap = map['moduleMap']
	
	//主构建项目名称
	def module = moduleMap['module']
	
	//编译项目标签
    def moduleTag = params[module]
	
	//镜像标签
	def imageTag = "${module}:${moduleTag}"	
	
	stage("deploy") {

            //docker运行参数
            
			//后台运行
            def daemonArgs = "-d"
			
			//容器名称
			def containerName = "--name ${module}"
			
            
			//工作目录存储卷
            //def workdirArgs = "-v ~/project/${module}/**/:/${module}/**/"
			
			//日志目录存储卷
            def logsArgs = "-v ~/project/${module}/logs/:/${module}/logs/"
            
            //时区设置
            def timeArgs = "-v /etc/timezone:/etc/timezone -v /etc/localtime:/etc/localtime"
            
            //加上jenkins传入自定义参数
            def allArgs = "${daemonArgs} ${containerName} ${logsArgs} ${timeArgs} ${dockerRunArgs}"
									            
									
			def deployScript = """
            
			#创建工作目录
            if [ ! -d "~/project/${module}" ]; then
                mkdir -p ~/project/${module}
				chmod +rw ~/project/${module}
            fi                        
            															
            #停止当前正在运行的该服务的docker容器,并删除旧的容器
            docker rm \$(docker stop ${module})
									
			#重命名一个镜像
			docker tag ${registry}/${imageTag} ${imageTag}
			
			#删除远程镜像仓库的镜像名
			docker image rm ${registry}/${imageTag}

            echo "${allArgs} ${imageTag}"
            #启动容器
            docker run ${allArgs} ${imageTag}
            """.stripIndent()
            writeFile(file: "deploy.sh", text: "${deployScript}")

            //远程部署
            for (String host in deployHosts.split(',')) {
				
				host = host.stripIndent()
			
				if(host.startsWith("\"")){					
					host = host.substring(1,host.length())					
				}
				if(host.endsWith("\"")){					
					host = host.substring(0,host.length()-1)					
				}
				//远程执行命令
				def remote = [:]
				remote.name = host
				remote.host = host
				remote.allowAnyHosts = true
				withCredentials([usernamePassword(credentialsId: deployedCredentialsId, passwordVariable: 'password', usernameVariable: 'username')]) {
					remote.user = username
					remote.password = password		
					
					//先把所有的旧镜像找出来			
					def oldImagesIds = sh returnStdout: true ,script: "docker images | grep ${module} | awk '{print \$3}'"
					
					//从镜像仓库拉取镜像至本地
					withCredentials([usernamePassword(credentialsId: registryCredentialsId, passwordVariable: 'dockerPassword', usernameVariable: 'dockerUser')]) {
						sshCommand remote: remote, command: "docker login -u ${dockerUser} -p ${dockerPassword} ${registry}"
						sshCommand remote: remote, command: "docker pull ${registry}/${imageTag}"																			
					}	
					
					//新镜像id
					def newImageId = sh returnStdout: true ,script: "docker images | grep ${registry}/${module} | awk '{print \$3}'"
					
					//远程执行部署脚本
					sshScript remote: remote, script: "deploy.sh"	
					
					if(oldImagesIds){					  					  
					  
					  def rmImagesIds = oldImagesIds.split('\n')
					  
					  def imagesIds = ''
					  
					  for(String imageId in rmImagesIds){					      
						  
					      //避免新的镜像id不变化
						  if(!imageId.trim().equals(newImageId.trim()) ){
						  						  						  
							 imagesIds = imagesIds+ " " + imageId
						  }						  
					  }
					  
					  println "旧镜像id："+oldImagesIds
					  
					  println "新镜像id："+newImageId
					  
					  println "需要删除的旧镜像id："+imagesIds
					  
					  if(imagesIds && imagesIds != ''){
						//#删除旧的镜像
						sshCommand remote: remote, command: "docker image rm ${imagesIds}"	
					  }						
					}
															
					
				}
			
			}
                    
    }

}

