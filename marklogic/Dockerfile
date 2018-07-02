# Based on https://developer.marklogic.com/blog/building-a-marklogic-docker-container
FROM centos:centos7

# Get any CentOS updates then clear the Docker cache
RUN yum -y update && yum clean all

# Install MarkLogic dependencies
RUN yum -y install glibc.i686 gdb.x86_64 redhat-lsb.x86_64 && yum clean all

# Install the initscripts package so MarkLogic starts ok
RUN yum -y install initscripts && yum clean all

# Set the Path
ENV PATH /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/MarkLogic/mlcmd/bin

# Install wget/unzip/sudo tools
RUN yum -y install wget unzip sudo && yum clean all

# Install JDK1.8
RUN yum install -y java-1.8.0-openjdk-headless && yum clean all
ENV JAVA_HOME /usr/lib/jvm/jre

# Copy the MarkLogic installer to a temp directory in the Docker image being built
COPY MarkLogic-9.0-6.x86_64.rpm /tmp/MarkLogic.rpm

# Install MarkLogic then delete the .RPM file if the install succeeded
RUN yum -y install /tmp/MarkLogic.rpm && rm /tmp/MarkLogic.rpm

# Expose MarkLogic Server ports
# Also expose any ports your own MarkLogic App Servers use such as
# HTTP, REST and XDBC App Servers for your applications
EXPOSE 7997 7998 7999 8000 8001 8002 8010

# Start MarkLogic from init.d script.
# Define default command (which avoids immediate shutdown)
CMD /etc/init.d/MarkLogic start && tail -f /dev/null